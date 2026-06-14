import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.core.type.DwgHandleRef;
import io.dwg.entities.*;
import io.dwg.entities.concrete.*;
import io.dwg.sections.objects.ObjectTypeResolver;
import io.dwg.sections.classes.DwgClassRegistry;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class TestWithParser {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;
        DwgClassRegistry classReg = new DwgClassRegistry();
        ObjectTypeResolver resolver = ObjectTypeResolver.defaultResolver(classReg);

        // 扫描整个文件，用 MS objSize + BS typeCode 模式
        System.out.println("=== 扫描整个文件 (0x5000 - 0x12000) ===");
        System.out.println("MS objSize + BS typeCode, common header: numReactors/entityMode/owner/xdict\n");

        Map<Long, DwgObject> objects = new HashMap<>();
        long nextHandle = 1;
        int pos = 0x5000;
        int end = 0x12000;
        int scanned = 0;
        int parsed = 0;
        Map<Integer, Integer> typeCount = new LinkedHashMap<>();

        while (pos < end - 4) {
            ByteBufferBitInput buf = new ByteBufferBitInput(
                ByteBuffer.wrap(data, pos, data.length - pos));
            BitStreamReader r = new BitStreamReader(buf, version);

            int objSize;
            try {
                objSize = r.readModularShort();
            } catch (Exception e) { pos++; continue; }

            if (objSize <= 0 || objSize > 0x4000) { pos++; continue; }

            // 尝试读 BS typeCode
            int typeCode;
            try {
                typeCode = r.readBitShort();
            } catch (Exception e) { pos++; continue; }

            if (typeCode < 0 || typeCode > 999) { pos++; continue; }

            // 验证: 下一个对象位置
            int nextOffset = pos + 2 + objSize;  // 简化的 R2000 公式
            if (nextOffset <= pos || nextOffset > end) { pos++; continue; }

            // 尝试解析这个对象
            DwgObject obj = createObject(typeCode);
            if (obj == null) {
                pos = nextOffset;
                scanned++;
                typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);
                continue;
            }

            try {
                // common header
                AbstractDwgObject ao = (AbstractDwgObject) obj;
                ao.setHandle(nextHandle++);
                ao.setRawTypeCode(typeCode);

                int numReactors = r.readBitLong();

                if (obj.isEntity() && obj instanceof AbstractDwgEntity) {
                    AbstractDwgEntity ae = (AbstractDwgEntity) obj;
                    int entityMode = buf.readBits(2);
                    ae.setEntityMode(entityMode);
                    buf.readBits(2);  // lineType flags
                }

                long ownerHandle = r.readHandle();
                ao.setOwnerHandle(new DwgHandleRef(ownerHandle));

                for (int i = 0; i < numReactors; i++) {
                    try { ao.addReactorHandle(new DwgHandleRef(r.readHandle())); }
                    catch (Exception ex) { break; }
                }

                // 类型特定的解析
                resolver.resolve(typeCode).ifPresent(reader -> {
                    try { reader.read(obj, r, version); }
                    catch (Exception e) {}
                });

                objects.put(ao.handle(), obj);
                parsed++;
                scanned++;
                typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);
                pos = nextOffset;
            } catch (Exception e) {
                scanned++;
                typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);
                pos = nextOffset;
            }
        }

        // 报告
        System.out.println("扫描了 " + scanned + " 个对象，成功解析 " + parsed + " 个");
        System.out.println("\n=== 类型统计 ===");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeCount.entrySet());
        sortedTypes.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<Integer, Integer> e : sortedTypes) {
            System.out.printf("  type=%3d (0x%02X): %4d  %s%n",
                e.getKey(), e.getKey(), e.getValue(), typeName(e.getKey()));
        }

        // BLOCK_HEADER
        System.out.println("\n=== BLOCK_HEADER (type=48) ===");
        int bhCount = 0;
        for (DwgObject obj : objects.values()) {
            if (obj.getRawTypeCode() == 48) {
                bhCount++;
                try {
                    DwgBlockHeader bh = (DwgBlockHeader) obj;
                    System.out.printf("  [%d] h=0x%x  name='%s'  flags=%d  base=(%.3f,%.3f,%.3f)%n",
                        bhCount, bh.handle(),
                        safeGet(() -> bh.getBlockName(), ""),
                        safeGet(() -> bh.getBlockFlags(), 0),
                        safeGet(() -> bh.getBasePoint(), null) != null ? bh.getBasePoint().x() : 0,
                        safeGet(() -> bh.getBasePoint(), null) != null ? bh.getBasePoint().y() : 0,
                        safeGet(() -> bh.getBasePoint(), null) != null ? bh.getBasePoint().z() : 0);
                } catch (Exception e) {
                    System.out.printf("  [%d] h=0x%x (parse err: %s)%n", bhCount, obj.getHandle(), e.getMessage());
                }
            }
        }
        System.out.println("总计: " + bhCount + " 个 BLOCK_HEADER");

        // INSERT
        System.out.println("\n=== INSERT (type=7) - 引用统计 ===");
        int insCount = 0;
        Map<String, Integer> refCount = new LinkedHashMap<>();
        // 构建 handle -> BLOCK_HEADER name
        Map<Long, String> handleNameMap = new HashMap<>();
        for (DwgObject obj : objects.values()) {
            if (obj.getRawTypeCode() == 48) {
                try {
                    DwgBlockHeader bh = (DwgBlockHeader) obj;
                    handleNameMap.put(bh.handle(), safeGet(() -> bh.getBlockName(), ""));
                } catch (Exception e) {}
            }
        }

        for (DwgObject obj : objects.values()) {
            if (obj.getRawTypeCode() == 7) {
                insCount++;
                try {
                    DwgInsert ins = (DwgInsert) obj;
                    long bhHandle = safeGet(() -> ins.getBlockHeaderHandle(), null) != null
                        ? (ins.getBlockHeaderHandle() instanceof Long
                            ? (Long) ins.getBlockHeaderHandle()
                            : (ins.getBlockHeaderHandle() instanceof DwgHandleRef
                                ? ((DwgHandleRef) ins.getBlockHeaderHandle()).handle()
                                : 0L))
                        : 0L;
                    String bname = handleNameMap.getOrDefault(bhHandle,
                        "UNKNOWN(h=0x" + Long.toHexString(bhHandle) + ")");
                    refCount.put(bname, refCount.getOrDefault(bname, 0) + 1);
                    if (insCount <= 25) {
                        System.out.printf("  [%d] block='%s'  pos=(%.2f,%.2f)  rot=%.4f%n",
                            insCount, bname,
                            safeGet(() -> ins.getInsertionPoint(), null) != null ? ins.getInsertionPoint().x() : 0,
                            safeGet(() -> ins.getInsertionPoint(), null) != null ? ins.getInsertionPoint().y() : 0,
                            safeGet(() -> ins.getRotationAngle(), 0.0));
                    }
                } catch (Exception e) {}
            }
        }
        System.out.println("总计: " + insCount + " 个 INSERT");
        System.out.println("\n引用统计:");
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            System.out.printf("  '%s': %d 次%n", e.getKey(), e.getValue());
        }

        // 最终输出
        System.out.println("\n==================================================");
        System.out.println("  FINAL RESULT: Block Definitions and References");
        System.out.println("==================================================");
        System.out.println("\n【块定义】共 " + bhCount + " 个:");
        int idx = 0;
        for (DwgObject obj : objects.values()) {
            if (obj.getRawTypeCode() == 48) {
                try {
                    DwgBlockHeader bh = (DwgBlockHeader) obj;
                    String name = safeGet(() -> bh.getBlockName(), "");
                    double bx = 0, by = 0, bz = 0;
                    try { bx = bh.getBasePoint().x(); by = bh.getBasePoint().y(); bz = bh.getBasePoint().z(); } catch (Exception ex) {}
                    int size = safeGet(() -> bh.getBlockFlags(), 0);
                    System.out.printf("  %d. '%s' (基点: %.3f, %.3f, %.3f, flags: %d)%n",
                        ++idx, name, bx, by, bz, size);
                } catch (Exception e) {}
            }
        }
        System.out.println("\n【引用统计】共 " + insCount + " 个 INSERT:");
        int total = 0;
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            total += e.getValue();
            System.out.printf("  - '%s': %d 次%n", e.getKey(), e.getValue());
        }
        System.out.println("\n总计: " + bhCount + " 个块定义, " + total + " 个 INSERT 引用");
    }

    @FunctionalInterface
    interface Func<T> { T get() throws Exception; }

    static <T> T safeGet(Func<T> f, T def) {
        try { return f.get(); } catch (Exception e) { return def; }
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
