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

public class BlockReport {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;
        ObjectTypeResolver resolver = ObjectTypeResolver.defaultResolver(new DwgClassRegistry());

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

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x4000) { pos++; continue; }

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 999) { pos++; continue; }

                int nextOffset = pos + 2 + objSize;
                if (nextOffset <= pos || nextOffset > end) { pos++; continue; }

                typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);

                DwgObject obj = createObject(typeCode);
                if (obj != null) {
                    AbstractDwgObject ao = (AbstractDwgObject) obj;
                    ao.setHandle(nextHandle++);
                    ao.setRawTypeCode(typeCode);

                    try {
                        int numReactors = r.readBitLong();
                        if (obj.isEntity() && obj instanceof AbstractDwgEntity) {
                            buf.readBits(2); buf.readBits(2);
                        }
                        ao.setOwnerHandle(new DwgHandleRef(r.readHandle()));
                        for (int i = 0; i < Math.min(numReactors, 20); i++) {
                            try { ao.addReactorHandle(new DwgHandleRef(r.readHandle())); }
                            catch (Exception ex) { break; }
                        }
                        resolver.resolve(typeCode).ifPresent(reader -> {
                            try { reader.read(obj, r, version); } catch (Exception e) {}
                        });
                        objects.put(ao.handle(), obj);
                        found++;
                    } catch (Exception e) {}
                }
                pos = nextOffset;
            } catch (Exception e) { pos++; }
        }

        System.out.println("==================================================");
        System.out.println("  FINAL RESULT: Block Definitions and References");
        System.out.println("==================================================");
        System.out.println("(Parsed " + found + " objects from " + data.length + " byte file)");

        // 块定义
        List<DwgBlockHeader> blockHeaders = new ArrayList<>();
        for (DwgObject obj : objects.values()) {
            if (obj.rawTypeCode() == 48 && obj instanceof DwgBlockHeader) {
                blockHeaders.add((DwgBlockHeader) obj);
            }
        }

        System.out.println("\n【块定义】共 " + blockHeaders.size() + " 个:");
        for (int i = 0; i < blockHeaders.size(); i++) {
            DwgBlockHeader bh = blockHeaders.get(i);
            String name = bh.blockName() != null ? bh.blockName() : "(未命名)";
            double bx=0, by=0, bz=0;
            if (bh.basePoint() != null) {
                bx = bh.basePoint().x(); by = bh.basePoint().y(); bz = bh.basePoint().z();
            }
            int flags = bh.flags();
            String xref = bh.xrefPath() != null ? bh.xrefPath() : "";
            System.out.printf("  %d. '%s' (handle=%d, 基点: %.3f,%.3f,%.3f, flags=%d, xref='%s')%n",
                i+1, name, bh.handle(), bx, by, bz, flags, xref);
        }

        // INSERT 引用
        List<DwgInsert> inserts = new ArrayList<>();
        for (DwgObject obj : objects.values()) {
            if (obj.rawTypeCode() == 7 && obj instanceof DwgInsert) {
                inserts.add((DwgInsert) obj);
            }
        }

        // 构建 handle -> block name 映射
        Map<Long, String> hmap = new HashMap<>();
        for (DwgBlockHeader bh : blockHeaders) {
            hmap.put(bh.handle(), bh.blockName() != null ? bh.blockName() : "");
        }

        // 引用统计
        Map<String, Integer> refCount = new LinkedHashMap<>();
        for (DwgInsert ins : inserts) {
            long bhRaw = 0;
            if (ins.blockHeaderHandle() != null) {
                bhRaw = ins.blockHeaderHandle().rawHandle();
            }
            String bname = hmap.getOrDefault(bhRaw, "*UNKNOWN(h=" + bhRaw + ")*");
            refCount.put(bname, refCount.getOrDefault(bname, 0) + 1);
        }

        System.out.println("\n【引用统计】共 " + inserts.size() + " 个 INSERT:");
        int total = 0;
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            total += e.getValue();
            System.out.printf("  - '%s': %d 次%n", e.getKey(), e.getValue());
        }

        // INSERT 详细
        System.out.println("\n【INSERT 详情】(前 25 个):");
        for (int i = 0; i < Math.min(25, inserts.size()); i++) {
            DwgInsert ins = inserts.get(i);
            long bhRaw = ins.blockHeaderHandle() != null ? ins.blockHeaderHandle().rawHandle() : 0;
            String bname = hmap.getOrDefault(bhRaw, "*UNKNOWN*");
            double ix=0, iy=0, iz=0;
            if (ins.insertionPoint() != null) {
                ix = ins.insertionPoint().x(); iy = ins.insertionPoint().y(); iz = ins.insertionPoint().z();
            }
            double rot = ins.rotation();
            System.out.printf("  [%d] block='%s' pos=(%.2f,%.2f,%.2f) scale=(%.4f,%.4f,%.4f) rot=%.4f%n",
                i+1, bname, ix, iy, iz, ins.xScale(), ins.yScale(), ins.zScale(), rot);
        }

        System.out.println("\n---------------------------------------");
        System.out.println("总计: " + blockHeaders.size() + " 个块定义, " + total + " 个 INSERT 引用");

        // 类型统计
        System.out.println("\n=== 对象类型统计 (Top 20) ===");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeCount.entrySet());
        sortedTypes.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (int i = 0; i < Math.min(20, sortedTypes.size()); i++) {
            Map.Entry<Integer, Integer> e = sortedTypes.get(i);
            System.out.printf("  type=%3d (0x%02X): %4d  %s%n",
                e.getKey(), e.getKey(), e.getValue(),
                DwgObjectType.fromCode(e.getKey()) != null
                    ? DwgObjectType.fromCode(e.getKey()).name()
                    : "UNKNOWN_" + e.getKey());
        }
    }

    static DwgObject createObject(int typeCode) {
        DwgObjectType type = DwgObjectType.fromCode(typeCode);
        if (type == null) return null;
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
}
