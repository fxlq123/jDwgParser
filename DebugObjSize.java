import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DebugObjSize {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        // 解析 handles
        List<long[]> handles = parseHandles(data, 0x11943, 4096);
        System.out.println("Handles: " + handles.size());

        // 显示前 30 个 handle 的原始字节
        System.out.println("\n=== 前 30 个对象的字节分析 ===");
        for (int i = 0; i < Math.min(30, handles.size()); i++) {
            long handle = handles.get(i)[0];
            long offset = handles.get(i)[1];
            if (offset < 0 || offset > data.length - 10) continue;

            System.out.printf("\n[%d] handle=0x%x offset=0x%x (字节: %02X %02X %02X %02X %02X %02X %02X %02X)%n",
                i, handle, offset,
                data[(int)offset] & 0xFF, data[(int)offset+1] & 0xFF,
                data[(int)offset+2] & 0xFF, data[(int)offset+3] & 0xFF,
                data[(int)offset+4] & 0xFF, data[(int)offset+5] & 0xFF,
                data[(int)offset+6] & 0xFF, data[(int)offset+7] & 0xFF);

            // 尝试不同的 objSize 解码
            // 1. LE uint16
            int le16 = (data[(int)offset] & 0xFF) | ((data[(int)offset+1] & 0xFF) << 8);
            System.out.printf("  LE uint16: objSize=%d%n", le16);

            // 2. modular short
            try {
                ByteBuffer bb = ByteBuffer.wrap(data);
                ByteBufferBitInput input = new ByteBufferBitInput(bb);
                input.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(input, version);
                int objSize = r.readModularShort();
                System.out.printf("  ModularShort: objSize=%d%n", objSize);
                // 然后 BS
                int typeCode = r.readBitShort();
                System.out.printf("  BitShort: typeCode=%d (0x%02X) = %s%n", typeCode, typeCode, typeName(typeCode));
            } catch (Exception e) {
                System.out.println("  ModularShort failed: " + e.getMessage());
            }

            // 3. 单字节 objSize
            int single = data[(int)offset] & 0xFF;
            System.out.printf("  Single byte: objSize=%d, then type=%d (0x%02X)%n",
                single, data[(int)offset+1] & 0xFF, data[(int)offset+1] & 0xFF);
        }
    }

    static String typeName(int code) {
        String[] names = {
            "UNUSED", "TEXT", "ATTDEF", "ATTRIB", "SEQEND", "ENDBLK", "", "INSERT", "MINSERT",
            "", "VERTEX_2D", "VERTEX_3D", "VERTEX_MESH", "VERTEX_PFACE", "VERTEX_PFACE_FACE",
            "POLYLINE_2D", "POLYLINE_3D", "ARC", "CIRCLE", "LINE",
            "DIM_ORD", "DIM_LIN", "DIM_ALIGN", "DIM_ANG3",
            "DIM_ANG2", "DIM_RAD", "DIM_DIA", "POINT", "FACE3D",
            "PLINE_PF", "PLINE_MESH", "SOLID", "TRACE", "SHAPE", "VIEWPORT",
            "ELLIPSE", "SPLINE", "REGION", "SOLID3D", "BODY", "RAY", "XLINE", "DICT",
            "", "MTEXT", "LEADER", "TOL", "MLINE", "BLK_HDR", "BLK_END",
            "LTYPE", "LAYER", "STYLE"
        };
        if (code >= 0 && code < names.length && names[code] != null && !names[code].isEmpty()) return names[code];
        switch(code) {
            case 0x3C: return "GROUP";
            case 0x4B: return "LWPLINE";
            case 0x4C: return "HATCH";
            case 0x4D: return "XRECORD";
            case 0x50: return "LAYOUT";
            default: return "?";
        }
    }

    static List<long[]> parseHandles(byte[] data, int offset, int maxBytes) {
        List<long[]> result = new ArrayList<>();
        int pos = offset;
        while (pos < offset + maxBytes && pos < data.length - 4) {
            int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
            pos += 2;
            if (pageSize <= 2 || pageSize > 2040) break;
            int pairsSize = pageSize - 2;
            int pairsRead = 0;
            long lastHandle = 0;
            long lastOffset = 0;
            while (pairsRead < pairsSize && pos < data.length - 2) {
                int hByte = data[pos++] & 0xFF;
                pairsRead++;
                long hDelta;
                if (hByte < 0x80) {
                    if (hByte == 0) break;
                    hDelta = hByte;
                } else {
                    int lo = hByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    hDelta = lo | ((long)hi << 7);
                    if (hDelta == 0) break;
                }
                lastHandle += hDelta;
                int oByte = data[pos++] & 0xFF;
                pairsRead++;
                long oDelta;
                if (oByte < 0x80) {
                    oDelta = (oByte > 63) ? (long)oByte - 128 : oByte;
                } else {
                    int lo = oByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    int combined = lo | (hi << 7);
                    oDelta = (combined > 16383) ? (long)combined - 32768 : combined;
                }
                lastOffset += oDelta;
                result.add(new long[]{ lastHandle, lastOffset });
            }
            pos += 2;
        }
        return result;
    }
}
