import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.core.type.DwgHandleRef;
import io.dwg.core.type.Point3D;
import io.dwg.entities.*;
import io.dwg.entities.concrete.*;
import io.dwg.sections.objects.ObjectTypeResolver;
import io.dwg.sections.classes.DwgClassRegistry;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class FinalResult {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;
        DwgClassRegistry classReg = new DwgClassRegistry();
        ObjectTypeResolver resolver = ObjectTypeResolver.defaultResolver(classReg);

        System.out.println("=== R2000 Object Stream Parser ===");
        System.out.println("File size: " + data.length + " bytes");
        System.out.println("Scanning 0x5000 - 0x12000\n");

        Map<Long, DwgObject> objects = new LinkedHashMap<>();
        Map<Integer, Integer> typeCount = new LinkedHashMap<>();
        long nextHandle = 1;
        int pos = 0x5000;
        int end = 0x12000;
        int found = 0;

        while (pos < end - 4) {
            try {
                ByteBufferBitInput buf = new ByteBufferBitInput(
                    ByteBuffer.wrap(data, pos, data.length - pos));
                BitStreamReader r = new BitStreamReader(buf, version);

                // MS: objSize
                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x4000) { pos++; continue; }

                // BS: typeCode
                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 999) { pos++; continue; }

                // 计算下一个位置: 根据 R2000 解析器的逻辑
                // objSize 是从 typeCode 之后到对象结尾的字节数
                int nextOffset = pos + 2 + objSize;  // 简化: 假设 MS 和 BS 各 1 字节
                if (nextOffset <= pos || nextOffset > end) { pos++; continue; }

                // 创建并解析对象
                DwgObject obj = createObject(typeCode);
                if (obj != null) {
                    AbstractDwgObject ao = (AbstractDwgObject) obj;
                    ao.setHandle(nextHandle++);
                    ao.setRawTypeCode(typeCode);

                    // common header
                    try {
                        int numReactors = r.readBitLong();
                        if (obj.isEntity() && obj instanceof AbstractDwgEntity) {
                            buf.readBits(2);
                            buf.readBits(2);
                        }
                        ao.setOwnerHandle(new DwgHandleRef(r.readHandle()));
                        for (int i = 0; i < Math.min(numReactors, 50); i++) {
                            try { ao.addReactorHandle(new DwgHandleRef(r.readHandle())); }
                            catch (Exception ex) { break; }
                        }

                        // type-specific
                        resolver.resolve(typeCode).ifPresent(reader -> {
                            try { reader.read(obj, r, version); } catch (Exception e) {}
                        });

                        objects.put(ao.handle(), obj);
                        found++;
                    } catch (Exception e) {}
                }

                typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);
                pos = nextOffset;
            } catch (Exception e) {
                pos++;
            }
        }

        System.out.println("Found: " + found + " objects\n");

        // 类型统计
        System.out.println("=== Type Distribution (Top 20) ===");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeCount.entrySet());
        sortedTypes.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (int i = 0; i < Math.min(20, sortedTypes.size()); i++) {
            Map.Entry<Integer, Integer> e = sortedTypes.get(i);
            System.out.printf("  type=%3d (0x%02X): %4d  %s%n",
                e.getKey(), e.getKey(), e.getValue(), typeName(e.getKey()));
        }

        // BLOCK_HEADER
        System.out.println("\n=== BLOCK_HEADER (type=48) ===");
        int bhCount = 0;
        for (DwgObject obj : objects.values()) {
            if (obj.rawTypeCode() == 48) {
                bhCount++;
                try {
                    DwgBlockHeader bh = (DwgBlockHeader) obj;
                    String name = bh.blockName();
                    int flags = bh.flags();
                    double bx=0, by=0, bz=0;
                    if (bh.basePoint() != null) {
                        bx = bh.basePoint().x(); by = bh.basePoint().y(); bz = bh.basePoint().z();
                    }
                    String xref = bh.xrefPath();
                    System.out.printf("  [%d] h=%d name='%s' flags=%d base=(%.3f,%.3f,%.3f) xref='%s'%n",
                        bhCount, bh.handle(),
                        name != null ? name : "",
                        flags, bx, by, bz, xref != null ? xref : "");
                } catch (Exception e) {
                    System.out.printf("  [%d] h=%d err: %s%n", bhCount, obj.handle(), e.getMessage());
                }
            }
        }
        System.out.println("Total: " + bhCount + " BLOCK_HEADER");

        // INSERT
        System.out.println("\n=== INSERT (type=7) - Block References ===");
        Map<String, Integer> refCount = new LinkedHashMap<>();
        // 构建 handle -> blockName 映射
        Map<Long, String> handleNameMap = new HashMap<>();
        for (DwgObject obj : objects.values()) {
            if (obj.rawTypeCode() == 48) {
                try {
                    DwgBlockHeader bh = (DwgBlockHeader) obj;
                    handleNameMap.put(bh.handle(), bh.blockName() != null ? bh.blockName() : "");
                } catch (Exception e) {}
            }
        }

        int insCount = 0;
        int shown = 0;
        for (DwgObject obj : objects.values()) {
            if (obj.rawTypeCode() == 7) {
                insCount++;
                try {
                    DwgInsert ins = (DwgInsert) obj;
                    long bhHandle = 0;
                    Object bhRef = ins.blockHeaderHandle();
                    if (bhRef instanceof Long) bhHandle = (Long) bhRef;
                    else if (bhRef instanceof DwgHandleRef) bhHandle = ((DwgHandleRef) bhRef).handle();
                    String bname = handleNameMap.getOrDefault(bhHandle,
                        "UNKNOWN(h=" + bhHandle + ")");
                    refCount.put(bname, refCount.getOrDefault(bname, 0) + 1);
                    if (shown++ < 30) {
                        double ix=0, iy=0, iz=0;
                        if (ins.insertionPoint() != null) {
                            ix = ins.insertionPoint().x(); iy = ins.insertionPoint().y(); iz = ins.insertionPoint().z();
                        }
                        double rot = ins.rotationAngle() != null ? ins.rotationAngle() : 0;
                        System.out.printf("  [%d] block='%s' pos=(%.2f,%.2f,%.2f) rot=%.4f%n",
                            insCount, bname, ix, iy, iz, rot);
                    }
                } catch (Exception e) {}
            }
        }
        System.out.println("Total: " + insCount + " INSERT");

        System.out.println("\n=== Block Reference Summary ===");
        int totalRefs = 0;
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            totalRefs += e.getValue();
        }
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            System.out.printf("  '%s': %d time(s)%n", e.getKey(), e.getValue());
        }

        // 最终结果
        System.out.println("\n==================================================");
        System.out.println("  FINAL RESULT: Block Definitions and References");
        System.out.println("==================================================");
        System.out.println("\n【块定义】共 " + bhCount + " 个:");
        int idx = 0;
        for (DwgObject obj : objects.values()) {
            if (obj.rawTypeCode() == 48) {
                try {
                    DwgBlockHeader bh = (DwgBlockHeader) obj;
                    String name = bh.blockName() != null ? bh.blockName() : "";
                    double bx=0, by=0, bz=0;
                    try { if (bh.basePoint() != null) { bx = bh.basePoint().x(); by = bh.basePoint().y(); bz = bh.basePoint().z(); } } catch (Exception ex) {}
                    System.out.printf("  %d. '%s' (基点: %.3f, %.3f, %.3f, 大小: %d 字节)%n",
                        ++idx, name, bx, by, bz, bh.flags());
                } catch (Exception e) {}
            }
        }
        System.out.println("\n【引用统计】共 " + totalRefs + " 个 INSERT:");
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            System.out.printf("  - '%s': %d 次%n", e.getKey(), e.getValue());
        }
        System.out.println("\n总计: " + bhCount + " 个块定义, " + totalRefs + " 个 INSERT 引用");
    }

    static DwgObject createObject(int typeCode) {
        DwgObjectType type = DwgObjectType.fromCode(typeCode);
        return switch (type) {
            case TEXT -> new DwgText();
            case ATTDEF -> new DwgAttdef();
            case ATTRIB -> new DwgAttrib();
            case SEQEND -> new DwgSeqEnd();
            case INSERT -> new DwgInsert();
            case MINSERT -> new DwgMinsert();
            case VERTEX_2D -> new DwgVertex2D();
            case VERTEX_3D -> new DwgVertex3D();
            case ARC -> new DwgArc();
            case CIRCLE -> new DwgCircle();
            case LINE -> new DwgLine();
            case POINT -> new DwgPoint();
            case MTEXT -> new DwgMText();
            case MLINE -> new DwgMLine();
            case BLOCK_HEADER -> new DwgBlockHeader();
            case BLOCK_END -> new DwgBlockEnd();
            case LAYER -> new DwgLayer();
            case LWPLINE -> new DwgLwPolyline();
            case HATCH -> new DwgHatch();
            case DICTIONARY -> new DwgDictionary();
            case LTYPE -> new DwgLtype();
            case STYLE -> new DwgStyle();
            case VIEW -> new DwgView();
            case UCS -> new DwgUcs();
            case VPORT -> new DwgVport();
            case APPID -> new DwgAppId();
            case DIMSTYLE -> new DwgDimStyle();
            case LAYOUT -> new DwgLayout();
            default -> null;
        };
    }

    static String typeName(int code) {
        return DwgObjectType.names.getOrDefault(code, "?");
    }
}
