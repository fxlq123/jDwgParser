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

public class BlockResult {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;
        ObjectTypeResolver resolver = ObjectTypeResolver.defaultResolver(new DwgClassRegistry());

        System.out.println("==================================================");
        System.out.println("  FINAL RESULT: Block Definitions and References");
        System.out.println("==================================================");
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("Version: R2000 (AC1015)");
        System.out.println("Size: " + data.length + " bytes");

        // 扫描整个文件
        Map<Long, DwgObject> objects = new LinkedHashMap<>();
        Map<Integer, Integer> typeCount = new LinkedHashMap<>();
        long nextHandle = 1;
        int found = 0;

        // 从已知的对象数据区域开始扫描
        // R2000: 对象在 section gaps 中，已知在 0x5000 之后
        int start = 0x5000;
        int end = Math.min(data.length - 16, 0x12000);

        System.out.println("\n扫描区域: 0x" + Integer.toHexString(start) + " - 0x" + Integer.toHexString(end));

        int pos = start;
        while (pos < end) {
            try {
                // 创建从 pos 开始的 byte 数组
                byte[] sub = Arrays.copyOfRange(data, pos, data.length);

                // 使用 ByteBufferBitInput 解析
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, version);

                // MS: 16-bit LE word
                int objSize = r.readModularShort();

                // 检查 objSize 有效性
                if (objSize <= 2 || objSize > 2000) {
                    pos++;
                    continue;
                }

                // BS: typeCode (从 bit 16 开始)
                int typeCode = r.readBitShort();

                // 检查 typeCode 有效性
                if (typeCode < 0 || typeCode > 500) {
                    pos++;
                    continue;
                }

                // 记录类型
                typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);

                // 尝试解析对象
                DwgObject obj = createObject(typeCode);
                if (obj != null) {
                    AbstractDwgObject ao = (AbstractDwgObject) obj;
                    ao.setHandle(nextHandle++);
                    ao.setRawTypeCode(typeCode);

                    try {
                        // Common header
                        int numReactors = r.readBitLong();
                        if (obj.isEntity() && obj instanceof AbstractDwgEntity) {
                            buf.readBits(2); // entityMode
                            buf.readBits(2); // lineType
                        }
                        ao.setOwnerHandle(new DwgHandleRef(r.readHandle()));
                        for (int i = 0; i < Math.min(numReactors, 20); i++) {
                            try { ao.addReactorHandle(new DwgHandleRef(r.readHandle())); }
                            catch (Exception ex) { break; }
                        }

                        // Type-specific parsing
                        resolver.resolve(typeCode).ifPresent(reader -> {
                            try { reader.read(obj, r, version); } catch (Exception e) {}
                        });

                        objects.put(ao.handle(), obj);
                        found++;
                    } catch (Exception e) {}
                }

                // 下一个对象: pos + 2 (MS 是 2 字节 LE)
                pos += 2;

            } catch (Exception e) {
                pos++;
            }
        }

        System.out.println("解析了 " + found + " 个对象");

        // BLOCK_HEADER
        List<DwgBlockHeader> blockHeaders = new ArrayList<>();
        for (DwgObject obj : objects.values()) {
            if (obj.rawTypeCode() == 48 && obj instanceof DwgBlockHeader) {
                blockHeaders.add((DwgBlockHeader) obj);
            }
        }

        System.out.println("\n【块定义】共 " + blockHeaders.size() + " 个:");
        int idx = 0;
        for (DwgBlockHeader bh : blockHeaders) {
            String name = bh.blockName() != null ? bh.blockName() : "(未命名)";
            double bx=0, by=0, bz=0;
            if (bh.basePoint() != null) {
                bx = bh.basePoint().x(); by = bh.basePoint().y(); bz = bh.basePoint().z();
            }
            System.out.printf("  %d. '%s' (handle=%d, 基点: %.3f, %.3f, %.3f)%n",
                ++idx, name, bh.handle(), bx, by, bz);
        }

        // INSERT
        List<DwgInsert> inserts = new ArrayList<>();
        for (DwgObject obj : objects.values()) {
            if (obj.rawTypeCode() == 7 && obj instanceof DwgInsert) {
                inserts.add((DwgInsert) obj);
            }
        }

        // 构建 handle -> block name 映射
        Map<Long, String> handleNameMap = new HashMap<>();
        for (DwgBlockHeader bh : blockHeaders) {
            handleNameMap.put(bh.handle(), bh.blockName() != null ? bh.blockName() : "");
        }

        // 引用统计
        Map<String, Integer> refCount = new LinkedHashMap<>();
        for (DwgInsert ins : inserts) {
            long bhRaw = ins.blockHeaderHandle() != null ? ins.blockHeaderHandle().rawHandle() : 0;
            String bname = handleNameMap.getOrDefault(bhRaw, "*UNKNOWN*");
            refCount.put(bname, refCount.getOrDefault(bname, 0) + 1);
        }

        System.out.println("\n【引用统计】共 " + inserts.size() + " 个 INSERT:");
        int total = 0;
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            total += e.getValue();
            System.out.printf("  - '%s': %d 次%n", e.getKey(), e.getValue());
        }

        // INSERT 详情
        System.out.println("\n【INSERT 详情】(前 30 个):");
        for (int i = 0; i < Math.min(30, inserts.size()); i++) {
            DwgInsert ins = inserts.get(i);
            long bhRaw = ins.blockHeaderHandle() != null ? ins.blockHeaderHandle().rawHandle() : 0;
            String bname = handleNameMap.getOrDefault(bhRaw, "*UNKNOWN*");
            double ix=0, iy=0, iz=0;
            if (ins.insertionPoint() != null) {
                ix = ins.insertionPoint().x(); iy = ins.insertionPoint().y(); iz = ins.insertionPoint().z();
            }
            System.out.printf("  [%d] block='%s' pos=(%.2f,%.2f,%.2f) rot=%.4f%n",
                i+1, bname, ix, iy, iz, ins.rotation());
        }

        System.out.println("\n---------------------------------------");
        System.out.println("总计: " + blockHeaders.size() + " 个块定义, " + total + " 个 INSERT 引用");

        // 类型统计
        System.out.println("\n=== 对象类型统计 (Top 15) ===");
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeCount.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (int i = 0; i < Math.min(15, sorted.size()); i++) {
            Map.Entry<Integer, Integer> e = sorted.get(i);
            DwgObjectType t = DwgObjectType.fromCode(e.getKey());
            String name = t != null ? t.name() : "UNKNOWN";
            System.out.printf("  type=%3d (0x%02X): %4d  %s%n", e.getKey(), e.getKey(), e.getValue(), name);
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
