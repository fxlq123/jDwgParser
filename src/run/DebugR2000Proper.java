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
 * 用正确的 bit-level 方法解析 R2000 BLOCK 和 INSERT 对象
 */
public class DebugR2000Proper {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);
        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        System.out.println("=== 正确解析 BLOCK 对象 ===\n");

        // 解析几个代表性的 BLOCK 对象
        long[] testHandles = {0x1L, 0x1bL, 0x1fL, 0x23L, 0xceL, 0xfbL, 0x2e2L};

        for (long h : testHandles) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;

            System.out.println("\n【BLOCK handle=0x" + Long.toHexString(h) + " @offset=" + offset + "】");

            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = reader.readModularShort();
            int typeCode = reader.readBitShort();
            System.out.println("  obj_size: " + objSize + " bytes");
            System.out.println("  typeCode: 0x" + Integer.toHexString(typeCode) + " (" + typeCode + ")");
            System.out.println("  current_bit_pos: " + bbuf.position());

            // 解析 object common data
            try {
                long ownerHandle = reader.readHandle();
                System.out.println("  owner_handle: 0x" + Long.toHexString(ownerHandle));

                int numReactors = reader.readBitShort();
                System.out.println("  num_reactors: " + numReactors);

                // 跳过 reactors
                for (int i = 0; i < Math.min(numReactors, 10); i++) {
                    long r = reader.readHandle();
                    System.out.println("  reactor[" + i + "]: 0x" + Long.toHexString(r));
                }

                long xdictHandle = reader.readHandle();
                System.out.println("  xdict_handle: 0x" + Long.toHexString(xdictHandle));

                // 现在是 BLOCK 特定字段
                // block_name: BS length + ASCII chars
                int nameLen = reader.readBitShort();
                System.out.println("  block_name length: " + nameLen + " (@bit=" + bbuf.position() + ")");

                if (nameLen > 0 && nameLen < 200) {
                    // 读取 nameLen bytes as ASCII
                    byte[] nameBytes = new byte[nameLen];
                    for (int i = 0; i < nameLen; i++) {
                        nameBytes[i] = (byte) reader.readBitLong(); // readBitLong() reads 8 bits
                    }
                    String blockName = new String(nameBytes, java.nio.charset.StandardCharsets.US_ASCII);
                    System.out.println("  block_name: '" + blockName + "'");
                }

                // flags (BS)
                int flags = reader.readBitShort();
                System.out.println("  block_flags: 0x" + Integer.toHexString(flags) + " (" + flags + ")");

                // base_point (3 BD)
                double x = reader.readBitDouble();
                double y = reader.readBitDouble();
                double z = reader.readBitDouble();
                System.out.printf("  base_point: (%.4f, %.4f, %.4f)%n", x, y, z);

                // xref_path (BS length + chars)
                int xrefLen = reader.readBitShort();
                System.out.println("  xref_path length: " + xrefLen);
                if (xrefLen > 0 && xrefLen < 200) {
                    byte[] xrefBytes = new byte[xrefLen];
                    for (int i = 0; i < xrefLen; i++) {
                        xrefBytes[i] = (byte) reader.readBitLong();
                    }
                    String xrefPath = new String(xrefBytes, java.nio.charset.StandardCharsets.US_ASCII);
                    System.out.println("  xref_path: '" + xrefPath + "'");
                }

                System.out.println("  final_bit_pos: " + bbuf.position());
                System.out.println("  消耗 bits: " + (bbuf.position() - offset * 8L));
                System.out.println("  消耗 bytes: " + ((bbuf.position() - offset * 8L + 7L) / 8L));

            } catch (Exception e) {
                System.out.println("  解析错误: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 解析 INSERT 实体
        System.out.println("\n=== 正确解析 INSERT 实体 ===\n");

        DwgDocument doc = DwgReader.defaultReader().open(data);
        List<Long> insertHandles = new ArrayList<>();
        for (long h : registry.allHandles()) {
            DwgObject obj = doc.objectMap().get(h);
            if (obj instanceof DwgInsert) {
                insertHandles.add(h);
            }
        }

        for (long h : insertHandles) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;

            System.out.println("\n【INSERT handle=0x" + Long.toHexString(h) + " @offset=" + offset + "】");

            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader reader = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = reader.readModularShort();
            int typeCode = reader.readBitShort();
            System.out.println("  obj_size: " + objSize + " bytes");
            System.out.println("  typeCode: 0x" + Integer.toHexString(typeCode) + " (" + typeCode + ")");

            try {
                // entity common data
                long ownerHandle = reader.readHandle();
                System.out.println("  owner_handle: 0x" + Long.toHexString(ownerHandle));

                // entity handle
                long entityHandle = reader.readHandle();
                System.out.println("  entity_handle: 0x" + Long.toHexString(entityHandle));

                // layer handle
                long layerHandle = reader.readHandle();
                System.out.println("  layer_handle: 0x" + Long.toHexString(layerHandle));

                // color index
                int colorIndex = reader.readBitShort();
                System.out.println("  color_index: " + colorIndex);

                // block_header_handle (关键)
                long blockHeaderHandle = reader.readHandle();
                System.out.println("  block_header_handle: 0x" + Long.toHexString(blockHeaderHandle));

                // scale factors (3 BD)
                double sx = reader.readBitDouble();
                double sy = reader.readBitDouble();
                double sz = reader.readBitDouble();
                System.out.printf("  scale: (%.4f, %.4f, %.4f)%n", sx, sy, sz);

                // rotation angle (BD)
                double rot = reader.readBitDouble();
                System.out.printf("  rotation: %.4f%n", rot);

                // insertion point (3 BD)
                double ix = reader.readBitDouble();
                double iy = reader.readBitDouble();
                double iz = reader.readBitDouble();
                System.out.printf("  insertion: (%.4f, %.4f, %.4f)%n", ix, iy, iz);

                // attribs follow flag
                int attribs = reader.readBitShort();
                System.out.println("  attribs_follow: " + attribs);

                System.out.println("  final_bit_pos: " + bbuf.position());

            } catch (Exception e) {
                System.out.println("  解析错误: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
