package io.dwg.sections.objects;

import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.SectionInputStream;
import io.dwg.core.type.DwgHandleRef;
import io.dwg.core.version.DwgVersion;
import io.dwg.entities.AbstractDwgEntity;
import io.dwg.entities.AbstractDwgObject;
import io.dwg.entities.DwgObject;
import io.dwg.entities.DwgObjectType;
import io.dwg.entities.concrete.*;
import io.dwg.format.common.SectionType;
import io.dwg.sections.AbstractSectionParser;
import io.dwg.sections.classes.DwgClassRegistry;
import io.dwg.sections.handles.HandleRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * 스펙 §20 AcDb:AcDbObjects 섹션 파서.
 */
public class ObjectsSectionParser extends AbstractSectionParser<Map<Long, DwgObject>> {

    private HandleRegistry handles;
    private DwgClassRegistry classRegistry;
    private ObjectTypeResolver resolver;

    public ObjectsSectionParser() {
        this.resolver = ObjectTypeResolver.defaultResolver(new DwgClassRegistry());
    }

    public void setHandleRegistry(HandleRegistry handles) { this.handles = handles; }
    public void setClassRegistry(DwgClassRegistry classRegistry) {
        this.classRegistry = classRegistry;
        this.resolver = ObjectTypeResolver.defaultResolver(this.classRegistry);
    }

    @Override
    public Map<Long, DwgObject> parse(SectionInputStream stream, DwgVersion version) throws Exception {
        Map<Long, DwgObject> result = new HashMap<>();

        boolean useSequentialParsing = false;

        if (handles != null && !handles.allHandles().isEmpty() && version.from(DwgVersion.R2007)) {
            byte[] raw = stream.rawBytes();
            long outOfRangeCount = 0;

            for (long h : handles.allHandles()) {
                var offset = handles.offsetFor(h);
                if (offset.isPresent()) {
                    long off = offset.get();
                    if (off < 0 || off >= raw.length) outOfRangeCount++;
                }
            }

            double invalidRatio = (double) outOfRangeCount / handles.allHandles().size();
            if (invalidRatio > 0.2) {
                useSequentialParsing = true;
            }
        }

        if (!useSequentialParsing && handles != null && !handles.allHandles().isEmpty()) {
            byte[] raw = stream.rawBytes();

            for (Map.Entry<Long, Long> entry : sortedHandleOffsets()) {
                long handle = entry.getKey();
                long offset = entry.getValue();

                if (offset < 0 || offset >= raw.length) {
                    continue;
                }

                try {
                    DwgObject obj = parseObjectAt(raw, (int) offset, version, handle);
                    if (obj != null) {
                        result.put(handle, obj);
                    }
                } catch (Exception e) {
                    // Silently skip failed objects
                }
            }
        } else {
            result = parseStreaming(stream, version);
        }

        return result;
    }

    private Map<Long, DwgObject> parseStreaming(SectionInputStream stream, DwgVersion version) throws Exception {
        Map<Long, DwgObject> result = new HashMap<>();
        byte[] raw = stream.rawBytes();
        long nextHandle = 1;
        long bitOffset = 0;

        // Create buffer once and seek per iteration — ByteBuffer.wrap() loses the offset
        // because the ByteBufferBitInput constructor calls buffer.position(0).
        ByteBufferBitInput bbuf = new ByteBufferBitInput(raw);

        while (bitOffset < (long)(raw.length - 6) * 8L) {
            long startBitOffset = bitOffset;
            try {
                bbuf.seek(bitOffset);
                BitStreamReader r = new BitStreamReader(bbuf, version);

                // MS returns object size in BYTES (libredwg: obj->size = bit_read_MS(dat); dat->size = obj->size)
                int objSizeBytes = r.readModularShort();

                // R2010+: UMC (handlestream_size in bits) comes between MS and type code.
                // obj->address = dat->byte after UMC; next object at (obj->address + obj->size).
                if (version.from(DwgVersion.R2010)) {
                    r.readUMC();
                }

                // Bit position of obj->address (start of object data, after MS+UMC)
                long objDataStartBit = bbuf.position();

                if (objSizeBytes <= 0 || objSizeBytes > 0x40000) {
                    bitOffset = startBitOffset + 16;
                    continue;
                }

                // R2010+ uses BOT; pre-R2010 uses BS
                int typeCode = version.from(DwgVersion.R2010) ? r.readBOT() : r.readBitShort();

                if (typeCode < 0 || typeCode > 5000) {
                    bitOffset = startBitOffset + 16;
                    continue;
                }

                DwgObject obj = createObject(typeCode);
                if (obj == null) {
                    // Next object starts at obj->address + obj->size (both in bytes → bits)
                    bitOffset = objDataStartBit + (long)objSizeBytes * 8L;
                    continue;
                }

                ((AbstractDwgObject) obj).setHandle(nextHandle);
                ((AbstractDwgObject) obj).setRawTypeCode(typeCode);

                boolean skipHeaderStreaming = isSkipHeaderType(typeCode);
                if (!skipHeaderStreaming) {
                    try {
                        parseCommonHeader(r, obj, version);
                    } catch (Exception e) {
                        // Common header parsing failed, object still stored
                    }
                }
                resolver.resolve(typeCode).ifPresent(reader -> {
                    try {
                        reader.read(obj, r, version);
                    } catch (Exception e) {
                        // Type-specific parsing failed silently
                    }
                });

                result.put(nextHandle, obj);
                nextHandle++;

                bitOffset = objDataStartBit + (long)objSizeBytes * 8L;

            } catch (Exception e) {
                bitOffset = startBitOffset + 16;
            }
        }

        return result;
    }

    private Iterable<Map.Entry<Long, Long>> sortedHandleOffsets() {
        Map<Long, Long> map = new HashMap<>();
        for (long h : handles.allHandles()) {
            handles.offsetFor(h).ifPresent(o -> map.put(h, o));
        }
        return map.entrySet();
    }

    private DwgObject parseObjectAt(byte[] raw, int byteOffset, DwgVersion version, long handle)
            throws Exception {
        ByteBufferBitInput buf = new ByteBufferBitInput(raw);
        buf.seek((long) byteOffset * 8L);
        BitStreamReader r = new BitStreamReader(buf, version);

        int objSize = r.readModularShort();
        if (objSize <= 0) return null;

        if (version.from(DwgVersion.R2010)) {
            r.readUMC();
        }

        int typeCode = version.from(DwgVersion.R2010) ? r.readBOT() : r.readBitShort();

        DwgObject obj = createObject(typeCode);
        if (obj == null) return null;

        ((AbstractDwgObject) obj).setHandle(handle);
        ((AbstractDwgObject) obj).setRawTypeCode(typeCode);

        boolean skipHeader = isSkipHeaderType(typeCode);
        if (!skipHeader) {
            try {
                parseCommonHeader(r, obj, version);
            } catch (IllegalStateException e) {
                if (e.getMessage() == null || !e.getMessage().contains("Invalid BL opcode")) throw e;
            }
        }

        resolver.resolve(typeCode).ifPresent(reader -> {
            try {
                reader.read(obj, r, version);
            } catch (Exception e) {
                // Type-specific parsing failed silently
            }
        });

        return obj;
    }

    private void parseCommonHeader(BitStreamReader r, DwgObject obj, DwgVersion version)
            throws Exception {
        AbstractDwgObject ao = (AbstractDwgObject) obj;

        int numReactors = r.readBitLong();

        if (numReactors > 100000) {
            numReactors = 0;
        }

        boolean hasXDic = false;
        if (version.from(DwgVersion.R2004)) {
            hasXDic = r.getInput().readBit();
        }

        if (obj.isEntity() && obj instanceof AbstractDwgEntity) {
            AbstractDwgEntity ae = (AbstractDwgEntity) obj;
            int entityMode = r.getInput().readBits(2);
            ae.setEntityMode(entityMode);

            if (version.from(DwgVersion.R2000)) {
                int ltFlags = r.getInput().readBits(2);
                if (ltFlags == 3) {
                    // plotStyleFlags
                }
            }
        }

        ao.setOwnerHandle(new DwgHandleRef(r.readHandle()));

        for (int i = 0; i < numReactors; i++) {
            ao.addReactorHandle(new DwgHandleRef(r.readHandle()));
        }

        if (hasXDic) {
            ao.setXDicHandle(new DwgHandleRef(r.readHandle()));
        }
    }

    private DwgObject createObject(int typeCode) {
        // 1. Try standard type code first
        DwgObjectType type = DwgObjectType.fromCode(typeCode);

        // 2. If unknown, check class registry for DXF name mapping
        if (type == DwgObjectType.UNKNOWN && classRegistry != null) {
            type = classRegistry.find(typeCode)
                .filter(def -> def.dxfRecordName() != null)
                .map(def -> resolveDxfName(def.dxfRecordName()))
                .orElse(DwgObjectType.UNKNOWN);
        }

        return switch (type) {
            case TEXT                -> new DwgText();
            case ATTDEF              -> new DwgAttdef();
            case ATTRIB              -> new DwgAttrib();
            case SEQEND              -> new DwgSeqEnd();
            case ENDBLK              -> new DwgBlockEnd();
            case INSERT              -> new DwgInsert();
            case MINSERT             -> new DwgMinsert();
            case VERTEX_2D           -> new DwgVertex2D();
            case VERTEX_3D           -> new DwgVertex3D();
            case VERTEX_MESH         -> new DwgVertexMesh();
            case VERTEX_PFACE        -> new DwgVertexPface();
            case VERTEX_PFACE_FACE   -> new DwgVertexPfaceFace();
            case POLYLINE_2D         -> new DwgPolyline2D();
            case POLYLINE_3D         -> new DwgPolyline3D();
            case ARC                 -> new DwgArc();
            case CIRCLE              -> new DwgCircle();
            case LINE                -> new DwgLine();
            case DIMENSION_ORDINATE  -> new DwgDimensionOrdinate();
            case DIMENSION_LINEAR    -> new DwgDimensionLinear();
            case DIMENSION_ALIGNED   -> new DwgDimensionAligned();
            case DIMENSION_ANG_3PT   -> new DwgDimensionAng3pt();
            case DIMENSION_ANG_2LN   -> new DwgDimensionAng2ln();
            case DIMENSION_RADIUS    -> new DwgDimensionRadius();
            case DIMENSION_DIAMETER  -> new DwgDimensionDiameter();
            case POINT               -> new DwgPoint();
            case FACE3D              -> new DwgFace3D();
            case POLYLINE_PFACE      -> new DwgPolylinePface();
            case POLYLINE_MESH       -> new DwgPolylineMesh();
            case SOLID               -> new DwgSolid();
            case TRACE               -> new DwgTrace();
            case SHAPE               -> new DwgShape();
            case VIEWPORT            -> new DwgViewport();
            case ELLIPSE             -> new DwgEllipse();
            case SPLINE              -> new DwgSpline();
            case REGION              -> new DwgRegion();
            case SOLID3D             -> new DwgSolid3d();
            case BODY                -> new DwgBody();
            case RAY                 -> new DwgRay();
            case XLINE               -> new DwgXLine();
            case DICTIONARY          -> new DwgDictionary();
            case MTEXT               -> new DwgMText();
            case LEADER              -> new DwgLeader();
            case TOLERANCE           -> new DwgTolerance();
            case MLINE               -> new DwgMLine();
            case BLOCK_HEADER        -> new DwgBlockHeader();
            case BLOCK_END           -> new DwgBlockEnd();
            case LAYER               -> new DwgLayer();
            case GROUP               -> new DwgGroup();
            case OLE2FRAME           -> new DwgOle2frame();
            case LWPLINE             -> new DwgLwPolyline();
            case HATCH               -> new DwgHatch();
            case XRECORD             -> new DwgXrecord();
            case LTYPE               -> new DwgLtype();
            case STYLE               -> new DwgStyle();
            case VIEW                -> new DwgView();
            case UCS                 -> new DwgUcs();
            case VPORT               -> new DwgVport();
            case APPID               -> new DwgAppId();
            case DIMSTYLE            -> new DwgDimStyle();
            case MLINESTYLE          -> new DwgMLineStyle();
            case LONG_TRANSACTION    -> new DwgLongTransaction();
            case LAYOUT              -> new DwgLayout();
            case PLACEHOLDER         -> new DwgPlaceholder();
            case VBA_PROJECT         -> new DwgVbaProject();
            case LAYOUT_ALTERNATE    -> new DwgLayout();
            case UNUSED              -> null;
            case VP_ENT_HDR          -> null;
            case STYLE_ALTERNATE     -> new DwgStyle();
            case APPID_CONTROL       -> new DwgXrecord();
            case APPID_ALTERNATE     -> new DwgAppId();
            case DIMSTYLE_CONTROL    -> new DwgXrecord();
            case DIMSTYLE_ALTERNATE  -> new DwgDimStyle();
            case VX_CONTROL          -> new DwgXrecord();
            case MLINESTYLE_ALTERNATE -> new DwgMLineStyle();
            case IMAGE               -> new DwgImage();
            case WIPEOUT             -> new DwgWipeout();
            case XREF                -> new DwgXref();
            case UNDERLAY            -> new DwgUnderlay();
            case SURFACE             -> new DwgSurface();
            case MESH                -> new DwgMesh();
            case SCALE               -> new DwgScale();
            case VISUALSTYLE         -> new DwgVisualStyle();
            case ACAD_FIELD          -> new DwgField();
            case ACAD_PROXY_ENTITY   -> new DwgProxyEntity();
            case ACAD_DICTIONARYVAR  -> new DwgDictionaryVar();
            case ACAD_TABLE          -> new DwgTable();
            case ACAD_SCALE_LIST     -> new DwgScaleList();
            case ACAD_TABLESTYLE     -> new DwgTableStyle();
            case ACAD_CELLSTYLE      -> new DwgCellStyle();
            case ACAD_PLOTSTYLE      -> new DwgPlotStyle();
            case ACAD_MATERIAL       -> new DwgMaterial();
            case ACAD_DATASOURCE     -> new DwgDataSource();
            case ACAD_PERSSUBENTMANAGER -> new DwgPersSubentManager();
            case UNKNOWN -> {
                // For unknown types, create DwgXrecord to allow parsing as generic object
                // This handles custom R2000 types (0xF401-0xFC01) and other extensions
                yield new DwgXrecord();
            }
        };
    }

    private boolean isSkipHeaderType(int typeCode) {
        // DICTIONARY and its variants
        if (typeCode == 0x2A) return true;
        // Alternate table entries
        if (typeCode == 0x35 || typeCode == 0x43 || typeCode == 0x45 ||
            typeCode == 0x49 || typeCode == 0x62 ||
            typeCode == 0x4F ||
            typeCode == 0x42 || typeCode == 0x44 || typeCode == 0x46) return true;
        // Old entity type code range (most R13-R2000 entities)
        if (typeCode >= 0x01 && typeCode <= 0x31) return true;
        // LWPLINE, HATCH, OLE2FRAME, IMAGE, UNDERLAY, SURFACE, MESH, ACAD_PROXY_ENTITY
        if (typeCode == 0x3E || typeCode == 0x4B || typeCode == 0x4C ||
            typeCode == 0x51 || typeCode == 0x52 ||
            typeCode == 0x54 || typeCode == 0x55 || typeCode == 0x56 ||
            typeCode == 0x5A) return true;
        // Extended range for R2007+ class numbers that are entities
        // Check class registry for entity classification
        if (classRegistry != null && classRegistry.find(typeCode).isPresent()) {
            return classRegistry.find(typeCode).get().isEntity();
        }
        return false;
    }

    private static final Map<String, DwgObjectType> DXF_TYPE_MAP = new HashMap<>();
    static {
        DXF_TYPE_MAP.put("ACDBBLOCKTABLE", DwgObjectType.BLOCK_HEADER);
        DXF_TYPE_MAP.put("BLOCK", DwgObjectType.BLOCK_HEADER);
        DXF_TYPE_MAP.put("BLOCK_HEADER", DwgObjectType.BLOCK_HEADER);
        DXF_TYPE_MAP.put("ACDBBLOCKENDBLOCKTABLE", DwgObjectType.BLOCK_END);
        DXF_TYPE_MAP.put("ENDBLK", DwgObjectType.BLOCK_END);
        DXF_TYPE_MAP.put("BLOCK_END", DwgObjectType.BLOCK_END);
        DXF_TYPE_MAP.put("ACDBINSERT", DwgObjectType.INSERT);
        DXF_TYPE_MAP.put("INSERT", DwgObjectType.INSERT);
        DXF_TYPE_MAP.put("ACDBMINSERT", DwgObjectType.MINSERT);
        DXF_TYPE_MAP.put("MINSERT", DwgObjectType.MINSERT);
        DXF_TYPE_MAP.put("ACDBLAYERTABLE", DwgObjectType.LAYER);
        DXF_TYPE_MAP.put("LAYER", DwgObjectType.LAYER);
        DXF_TYPE_MAP.put("ACDBLINETYPETABLE", DwgObjectType.LTYPE);
        DXF_TYPE_MAP.put("LTYPE", DwgObjectType.LTYPE);
        DXF_TYPE_MAP.put("ACDBSTYLETABLE", DwgObjectType.STYLE);
        DXF_TYPE_MAP.put("STYLE", DwgObjectType.STYLE);
        DXF_TYPE_MAP.put("ACDBVIEWTABLE", DwgObjectType.VIEW);
        DXF_TYPE_MAP.put("VIEW", DwgObjectType.VIEW);
        DXF_TYPE_MAP.put("ACDBUCSTABLE", DwgObjectType.UCS);
        DXF_TYPE_MAP.put("UCS", DwgObjectType.UCS);
        DXF_TYPE_MAP.put("ACDBVPORTTABLE", DwgObjectType.VPORT);
        DXF_TYPE_MAP.put("VPORT", DwgObjectType.VPORT);
        DXF_TYPE_MAP.put("ACDBAPPTABLE", DwgObjectType.APPID);
        DXF_TYPE_MAP.put("APPID", DwgObjectType.APPID);
        DXF_TYPE_MAP.put("ACDBDIMSTYLETABLE", DwgObjectType.DIMSTYLE);
        DXF_TYPE_MAP.put("DIMSTYLE", DwgObjectType.DIMSTYLE);
        DXF_TYPE_MAP.put("ACDBDICTIONARY", DwgObjectType.DICTIONARY);
        DXF_TYPE_MAP.put("DICTIONARY", DwgObjectType.DICTIONARY);
        DXF_TYPE_MAP.put("ACDBDICTIONARYVAR", DwgObjectType.ACAD_DICTIONARYVAR);
        DXF_TYPE_MAP.put("DICTIONARYVAR", DwgObjectType.ACAD_DICTIONARYVAR);
        DXF_TYPE_MAP.put("ACDBDICTIONARYWDFLT", DwgObjectType.DICTIONARY);
        DXF_TYPE_MAP.put("ACDBPLACEHOLDER", DwgObjectType.PLACEHOLDER);
        DXF_TYPE_MAP.put("PLACEHOLDER", DwgObjectType.PLACEHOLDER);
        DXF_TYPE_MAP.put("ACDBXRECORD", DwgObjectType.XRECORD);
        DXF_TYPE_MAP.put("XRECORD", DwgObjectType.XRECORD);
        DXF_TYPE_MAP.put("ACDBLAYOUT", DwgObjectType.LAYOUT);
        DXF_TYPE_MAP.put("LAYOUT", DwgObjectType.LAYOUT);
        DXF_TYPE_MAP.put("ACDBSCALELIST", DwgObjectType.ACAD_SCALE_LIST);
        DXF_TYPE_MAP.put("SCALE_LIST", DwgObjectType.ACAD_SCALE_LIST);
        DXF_TYPE_MAP.put("ACDBSCALE", DwgObjectType.SCALE);
        DXF_TYPE_MAP.put("ACDBTABLE", DwgObjectType.ACAD_TABLE);
        DXF_TYPE_MAP.put("ACDBTABLESTYLE", DwgObjectType.ACAD_TABLESTYLE);
        DXF_TYPE_MAP.put("ACDBCELLSTYLE", DwgObjectType.ACAD_CELLSTYLE);
        DXF_TYPE_MAP.put("ACDBPLOTSTYLENAME", DwgObjectType.ACAD_PLOTSTYLE);
        DXF_TYPE_MAP.put("ACDBMATERIAL", DwgObjectType.ACAD_MATERIAL);
        DXF_TYPE_MAP.put("ACDBVISUALSTYLE", DwgObjectType.VISUALSTYLE);
        DXF_TYPE_MAP.put("VISUALSTYLE", DwgObjectType.VISUALSTYLE);
        DXF_TYPE_MAP.put("ACDBFIELD", DwgObjectType.ACAD_FIELD);
        DXF_TYPE_MAP.put("ACDBTABLECONTENT", DwgObjectType.ACAD_TABLE);
        DXF_TYPE_MAP.put("ACDBSORTENTSTABLE", DwgObjectType.XRECORD);
        DXF_TYPE_MAP.put("ACDBMLINESTYLE", DwgObjectType.MLINESTYLE);
    }

    private static DwgObjectType resolveDxfName(String dxfName) {
        if (dxfName == null) return DwgObjectType.UNKNOWN;
        String key = dxfName.toUpperCase();
        DwgObjectType t = DXF_TYPE_MAP.get(key);
        if (t != null) return t;
        return DwgObjectType.UNKNOWN;
    }

    @Override
    public String sectionName() {
        return SectionType.OBJECTS.sectionName();
    }
}
