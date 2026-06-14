package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.Paths;

/**
 * 重要发现: 用 object_common (owner+H+num_reactors+xdict) 而非 entity_common
 * 来解析 BLOCK (0x31) 和 INSERT (0x07) - 因为它们是对象不是实体!
 *
 * wait: 实际上 BLOCK 是 "block record"，在 ODA 中它是 AcDbBlockTableRecord,
 * 既是 Object 也是 Entity (继承自 AcDbEntity). 但就 *DWG 文件编码*而言,
 * 它应该有 object_common 而不是 entity_common.
 *
 * 让我先检查: readHandleRef vs readHandle
 * readHandleRef(owner): 先读一个 H, 然后根据 code (高 4 位) 解释为
 *   0=absolute, 1=relative to owner, 2=relative negative, 4=previous owned, 8=next owned
 *
 * 这可能是关键! 我们一直在用 readHandle(), 但字段实际上是 handle reference,
 * 需要用 readHandleRef(ownerHandle) 来解析.
 *
 * 让我用 object_common + readHandleRef 重新解析:
 */
public class AnalyzeR2000HandleRef {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println("========== 用 readHandleRef 解析 BLOCK @ 23203 ==========");
        traceBlockWithRef(23203);
        traceBlockWithRef(21677);

        System.out.println("\n========== 用 readHandleRef 解析 BLOCK_HEADER @ 21177 ==========");
        traceBlockHeaderWithRef(21177);

        System.out.println("\n========== 用 readHandleRef 解析 INSERT @ 50409 ==========");
        traceInsertWithRef(50409);
    }

    private static void traceBlockWithRef(int offset) {
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L + 26);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        System.out.println("Start pos = " + bb.position());

        // object_common: owner_handle(H) + num_reactors(BS) + H*num + xdict_handle(H)
        long owner = r.readHandle();
        System.out.println("1. owner_handle = 0x" + Long.toHexString(owner));

        int numReactors = r.readBitShort();
        System.out.println("2. num_reactors(BS) = " + numReactors + " [pos=" + bb.position() + "]");

        if (numReactors > 0 && numReactors < 50) {
            for (int i = 0; i < numReactors; i++) {
                long rh = r.readHandleRef(owner);
                System.out.println("   reactor[" + i + "] = 0x" + Long.toHexString(rh));
            }
        }

        long xdict = r.readHandleRef(owner);
        System.out.println("3. xdict_handle(ref) = 0x" + Long.toHexString(xdict) + " [pos=" + bb.position() + "]");

        long pos1 = bb.position();
        System.out.println("   object_common 结束: byte " + (pos1/8 - offset) + " extra " + pos1%8);

        // 现在 block 特定字段
        // block_name (TV/TU)
        try {
            int nameLen = r.readBitShort();
            System.out.println("4. block_name BS length = " + nameLen);
            if (nameLen > 0 && nameLen < 200) {
                // try ASCII
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen; i++) {
                    int b = r.getInput().readBits(8) & 0xFF;
                    if (b >= 32 && b < 127) sb.append((char)b);
                    else sb.append('?');
                }
                System.out.println("   (ASCII): \"" + sb + "\"");
            }
        } catch (Exception e) {
            System.out.println("   [fail] " + e.getMessage());
        }

        // 等等，也许 block_name 有不同的字段顺序
        // 让我看看 entity_header + entity_data 会不会更好
        // entity_common (不是 object_common): 对 BLOCK 实体而言
        // 实际上 BLOCK 是 entity! 所以它应该有 entity_common 字段...
        // 但我们之前读到 preview=true, ent_mode=0, color=0, xdict=huge value...
        // 这显然不对

        // 回到根本问题: block_name 字符串 "Paper_Space" (11 chars)
        // 在 bytes 12-22 (0-indexed). 
        // 所以从 offset+12*8 = offset*8 + 96 开始是 block_name 的第一个字节
        // 但 block_name 不是 *必须* 从 0th byte 开始 - 可以从任何 byte 开始
        // 它的长度 = 11， 所以 BS value = 11
        // BS format: 2 bits opcode + conditional data
        //   opcode=00 → 16-bit LE value
        //   opcode=01 → 8-bit value
        //   opcode=10 → return 0
        //   opcode=11 → return 256
        // 11 < 256, 所以如果 opcode=01, value=11 (8 bits: 0000_1011)
        // 那么 BS(11) 占用 2+8 = 10 bits
        // 这意味着 BS 在 byte "x" 处开始，块名称的第一个字节从 bit (x*8 + 10) 开始
        // 即 byte x+2 (如果是字节对齐会很奇怪)

        // 让我试试: block_name 直接作为 ASCII 字符串从 entity_common 结束位置开始
        // 看看 entity_common 的实际位置和内容

        System.out.println("   [bytes around entity_common_end, dump ASCII]:");
        int startByte = (int)(pos1 / 8);
        for (int i = 0; i < 30; i++) {
            int b = data[startByte + i] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? (char)b : '.');
        }
        System.out.println();
    }

    private static void traceBlockHeaderWithRef(int offset) {
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L + 26);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        long owner = r.readHandle();
        System.out.println("1. owner_handle = 0x" + Long.toHexString(owner));

        int numReactors = r.readBitShort();
        System.out.println("2. num_reactors(BS) = " + numReactors);

        if (numReactors > 0 && numReactors < 50) {
            for (int i = 0; i < numReactors; i++) {
                long rh = r.readHandleRef(owner);
                System.out.println("   reactor[" + i + "] = 0x" + Long.toHexString(rh));
            }
        }

        long xdict = r.readHandleRef(owner);
        System.out.println("3. xdict_handle(ref) = 0x" + Long.toHexString(xdict) + " [pos=" + bb.position() + "]");

        long pos1 = bb.position();

        // block_name
        try {
            int nameLen = r.readBitShort();
            System.out.println("4. block_name BS length = " + nameLen);
            if (nameLen > 0 && nameLen < 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen; i++) {
                    int b = r.getInput().readBits(8) & 0xFF;
                    if (b >= 32 && b < 127) sb.append((char)b);
                    else sb.append('?');
                }
                System.out.println("   (ASCII): \"" + sb + "\"");
            }
        } catch (Exception e) {
            System.out.println("   [fail] " + e.getMessage());
        }

        // 从 pos1 开始的原始 ASCII dump
        int startByte = (int)(pos1 / 8);
        System.out.print("   [ASCII from byte " + (startByte - offset) + "]: ");
        for (int i = 0; i < 30; i++) {
            int b = data[startByte + i] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? (char)b : '.');
        }
        System.out.println();
    }

    private static void traceInsertWithRef(int offset) {
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L + 26);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        System.out.println("Insert start pos = " + bb.position());

        // object_common
        long owner = r.readHandle();
        System.out.println("1. owner_handle = 0x" + Long.toHexString(owner));

        int numReactors = r.readBitShort();
        System.out.println("2. num_reactors = " + numReactors);

        if (numReactors > 0 && numReactors < 50) {
            for (int i = 0; i < numReactors; i++) {
                long rh = r.readHandleRef(owner);
                System.out.println("   reactor[" + i + "] = 0x" + Long.toHexString(rh));
            }
        }

        long xdict = r.readHandleRef(owner);
        System.out.println("3. xdict_handle(ref) = 0x" + Long.toHexString(xdict) + " [pos=" + bb.position() + "]");

        long pos1 = bb.position();
        System.out.println("   object_common end: byte " + (pos1/8 - offset) + " extra " + pos1%8);

        // block_header_handle (H - ref)
        long bhh = r.readHandleRef(owner);
        System.out.println("4. block_header_handle(ref) = 0x" + Long.toHexString(bhh) + " [pos=" + bb.position() + "]");

        // 3BD insert_point
        try {
            double x = r.readBitDouble();
            double y = r.readBitDouble();
            double z = r.readBitDouble();
            System.out.printf("5. insert_point: (%.4f, %.4f, %.4f)%n", x, y, z);

            double sx = r.readBitDouble();
            double sy = r.readBitDouble();
            double sz = r.readBitDouble();
            System.out.printf("6. scale: (%.4f, %.4f, %.4f)%n", sx, sy, sz);

            double rot = r.readBitDouble();
            System.out.printf("7. rotation: %.6f rad%n", rot);
        } catch (Exception e) {
            System.out.println("   [BD fail] " + e.getMessage());
        }
    }
}
