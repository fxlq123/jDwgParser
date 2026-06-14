package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;

import java.nio.file.Paths;
import java.util.*;

/**
 * 用正确的 BitStreamReader 方法深入解析 R2000 BLOCK_HEADER 对象
 */
public class DebugR2000BlockProper {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 获取 handle registry
        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        List<Long> sortedHandles = new ArrayList<>();
        for (long h : registry.allHandles()) sortedHandles.add(h);
        Collections.sort(sortedHandles);

        System.out.println("=== 正确的位级解析 BLOCK 对象 ===\n");

        // 解析第一个 BLOCK (type=0x30, handle=1)
        parseBlockObject(data, 1L, registry.offsetFor(1L).orElse(-1L), "BLOCK_HEADER #1");

        // 解析 BLOCK (type=0x31, handle=0x1b)
        parseBlockObject(data, 0x1bL, registry.offsetFor(0x1bL).orElse(-1L), "*Paper_Space?");

        // 解析 BLOCK (type=0x31, handle=0x1f)
        parseBlockObject(data, 0x1fL, registry.offsetFor(0x1fL).orElse(-1L), "*Model_Space?");

        // 解析 BLOCK (type=0x31, handle=0x23)
        parseBlockObject(data, 0x23L, registry.offsetFor(0x23L).orElse(-1L), "*Paper_Space?");

        // 解析 BLOCK (type=0x31, handle=0xce)
        parseBlockObject(data, 0xceL, registry.offsetFor(0xceL).orElse(-1L), "SW_NOTE_0?");

        // 解析 BLOCK (type=0x31, handle=0xfb)
        parseBlockObject(data, 0xfbL, registry.offsetFor(0xfbL).orElse(-1L), "SW_NOTE_0_1?");

        // 现在分析 INSERT 实体的 block_header_handle
        System.out.println("\n=== 分析 INSERT 实体 ===\n");
        DwgDocument doc = DwgReader.defaultReader().open(data);
        for (long h : sortedHandles) {
            DwgObject obj = doc.objectMap().get(h);
            if (obj instanceof DwgInsert) {
                parseInsertEntity(data, h, registry.offsetFor(h).orElse(-1L), registry);
                break;
            }
        }
    }

    private static void parseBlockObject(byte[] data, long handle, long offset, String label) {
        if (offset < 0) {
            System.out.println(label + ": 无效 offset");
            return;
        }

        try {
            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

            System.out.println("\n【" + label + "】 handle=0x" + Long.toHexString(handle) +
                " @offset=" + offset);

            // 1. obj_size (MS)
            int objSize = reader.readModularShort();
            System.out.println("  obj_size: " + objSize + " @bit=" + bbuf.position());

            // 2. type_code (BS)
            int typeCode = reader.readBitShort();
            System.out.println("  type_code: 0x" + Integer.toHexString(typeCode) + " (" + typeCode + ") @bit=" + bbuf.position());

            // 3. Object common data:
            //    - no_links (B, 1bit) [可选]
            //    - handle_to_owner_object (H)
            //    - num_reactors (BS)
            //    - reactor_handles (num_reactors * H)
            //    - xdict_obj_handle (H)
            //    - num_attr (BS) [可选]
            //    - version (BS) [可选]

            // 尝试读取 handle to owner
            long startBit = bbuf.position();
            long ownerHandle = reader.readHandle();
            System.out.println("  handle_to_owner: 0x" + Long.toHexString(ownerHandle) +
                " @bit=" + bbuf.position() + " (read " + (bbuf.position()-startBit) + " bits)");

            // num_reactors
            int numReactors = reader.readBitShort();
            System.out.println("  num_reactors: " + numReactors + " @bit=" + bbuf.position());

            // reactor handles
            for (int i = 0; i < Math.min(numReactors, 10); i++) {
                long r = reader.readHandle();
                System.out.println("  reactor_handle[" + i + "]: 0x" + Long.toHexString(r));
            }

            // xdict_obj_handle
            long xdictHandle = reader.readHandle();
            System.out.println("  xdict_obj_handle: 0x" + Long.toHexString(xdictHandle) +
                " @bit=" + bbuf.position());

            // 现在是 BLOCK_HEADER specific data:
            // 对于 type=0x30 (BLOCK_HEADER):
            //   block_name (TU)
            //   flags (BS)
            //   base_point (3BD)
            //   xref_path (TU)
            //
            // 对于 type=0x31 (BLOCK):
            //   ???

            // 尝试读取 block_name
            try {
                int textLen = reader.readBitShort();
                System.out.println("  block_name length (BS): " + textLen + " @bit=" + bbuf.position());

                if (textLen > 0 && textLen < 100) {
                    // 读取 textLen 个字节作为 ASCII
                    byte[] bytes = new byte[textLen];
                    for (int i = 0; i < textLen; i++) {
                        int b = 0;
                        // 使用反射或直接读 bits
                        try {
                            java.lang.reflect.Method m = ByteBufferBitInput.class.getDeclaredMethod("readBits", int.class);
                            m.setAccessible(true);
                            b = (int) m.invoke(bbuf, 8);
                        } catch (Exception ex) { b = 0; }
                        bytes[i] = (byte) b;
                    }
                    String blockName = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
                    System.out.println("  block_name: '" + blockName + "'");
                } else {
                    // 回退 - 可能这个字段在 type=0x30 中的位置不同
                    System.out.println("  (block_name length 无效, 可能是其他字段)");
                }
            } catch (Exception e) {
                System.out.println("  (block_name 读取失败: " + e.getMessage() + ")");
            }

            // 对于 type=0x30, 可能它是一个 block table, 而具体的 block 定义是 type=0x31
            // 让我检查 type=0x30 的对象是否真的是 block_header 容器

            // 显示对象剩余字节的 ASCII 内容
            long remainingBytes = objSize - ((bbuf.position() - offset * 8L) / 8L);
            if (remainingBytes > 0 && remainingBytes < objSize) {
                int bytePos = (int)(bbuf.position() / 8);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(remainingBytes, 100); i++) {
                    if (bytePos + i >= data.length) break;
                    int b = data[bytePos + i] & 0xFF;
                    sb.append((b >= 32 && b < 127) ? (char)b : '.');
                }
                System.out.println("  剩余字节 ASCII: '" + sb + "'");
            }

            // 详细显示对象末尾部分
            System.out.print("  对象结束 bytes: ");
            int endByte = (int)offset + objSize;
            for (int i = Math.max(0, objSize - 10); i < objSize; i++) {
                if ((int)offset + i < data.length) {
                    System.out.printf("%02X ", data[(int)offset + i] & 0xFF);
                }
            }
            System.out.println();

        } catch (Exception e) {
            System.out.println("  解析错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void parseInsertEntity(byte[] data, long handle, long offset, HandleRegistry registry) {
        if (offset < 0) return;

        try {
            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

            System.out.println("【INSERT】 handle=0x" + Long.toHexString(handle) + " @offset=" + offset);

            int objSize = reader.readModularShort();
            System.out.println("  obj_size: " + objSize);

            int typeCode = reader.readBitShort();
            System.out.println("  type_code: 0x" + Integer.toHexString(typeCode) + " (" + typeCode + ")");

            // entity common data:
            // handle_to_owner (H)
            long ownerHandle = reader.readHandle();
            System.out.println("  handle_to_owner: 0x" + Long.toHexString(ownerHandle));

            // num_attr (BS) [R2000 specific - 可能没有]
            // entity handle (H)
            long entHandle = reader.readHandle();
            System.out.println("  entity_handle: 0x" + Long.toHexString(entHandle));

            // layer handle (H)
            long layerHandle = reader.readHandle();
            System.out.println("  layer_handle: 0x" + Long.toHexString(layerHandle));

            // linetype handle (H) [可能]
            long ltypeHandle = reader.readHandle();
            System.out.println("  linetype_handle: 0x" + Long.toHexString(ltypeHandle));

            // color index (BS)
            int colorIndex = reader.readBitShort();
            System.out.println("  color_index: " + colorIndex);

            // linetype scale (BD) [可选]
            double scale = reader.readBitDouble();
            System.out.println("  linetype_scale: " + scale);

            // visibility (BS)
            int visibility = reader.readBitShort();
            System.out.println("  visibility: " + visibility);

            // lineweight (BS)
            int lineweight = reader.readBitShort();
            System.out.println("  lineweight: " + lineweight);

            // INSERT specific data:
            // block_header_handle (H)
            long blockHeaderHandle = reader.readHandle();
            System.out.println("  block_header_handle: 0x" + Long.toHexString(blockHeaderHandle) +
                " -> 在 registry 中存在吗? " + registry.offsetFor(blockHeaderHandle));

            // insertion point (3BD)
            double[] pt = reader.read3BitDouble();
            System.out.printf("  insertion_point: (%.4f, %.4f, %.4f)%n", pt[0], pt[1], pt[2]);

            // scale factor (3BD)
            double[] sf = reader.read3BitDouble();
            System.out.printf("  scale_factor: (%.4f, %.4f, %.4f)%n", sf[0], sf[1], sf[2]);

            // rotation angle (BD)
            double rot = reader.readBitDouble();
            System.out.printf("  rotation_angle: %.4f%n", rot);

        } catch (Exception e) {
            System.out.println("  解析错误: " + e.getMessage());
        }
    }
}
