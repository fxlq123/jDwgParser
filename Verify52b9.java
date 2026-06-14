import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Verify52b9 {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 验证 0x52b9 处的对象 ===");
        int pos = 0x52b9;

        System.out.print("原始字节 @ 0x" + Integer.toHexString(pos) + ": ");
        for (int i = 0; i < 20; i++) {
            System.out.printf("%02X ", data[pos + i] & 0xFF);
        }
        System.out.println();

        // 解析
        byte[] sub = Arrays.copyOfRange(data, pos, data.length);
        ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
        BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

        int ms = r.readModularShort();
        int tc = r.readBitShort();
        System.out.println("MS = " + ms + ", type = " + tc);

        // 下一个位置
        int next = pos + 2 + ms;
        System.out.println("下一个对象 @ 0x" + Integer.toHexString(next));

        System.out.print("字节 @ 0x" + Integer.toHexString(next) + ": ");
        for (int i = 0; i < 10; i++) {
            if (next + i < data.length)
                System.out.printf("%02X ", data[next + i] & 0xFF);
        }
        System.out.println();

        // 解析下一个
        if (next < data.length - 4) {
            byte[] sub2 = Arrays.copyOfRange(data, next, data.length);
            ByteBufferBitInput buf2 = new ByteBufferBitInput(ByteBuffer.wrap(sub2));
            BitStreamReader r2 = new BitStreamReader(buf2, DwgVersion.R2000);
            int ms2 = r2.readModularShort();
            int tc2 = r2.readBitShort();
            System.out.println("下一个: MS = " + ms2 + ", type = " + tc2);
        }

        // 扫描多个对象，找到 BLOCK_HEADER 和 BLOCK_END
        System.out.println("\n=== 扫描前 50 个对象 ===");
        int scanPos = pos;
        for (int i = 0; i < 50 && scanPos < data.length - 4; i++) {
            if (data[scanPos] == 0) { scanPos++; continue; }

            try {
                byte[] s = Arrays.copyOfRange(data, scanPos, data.length);
                ByteBufferBitInput b = new ByteBufferBitInput(ByteBuffer.wrap(s));
                BitStreamReader reader = new BitStreamReader(b, DwgVersion.R2000);

                int objSize = reader.readModularShort();
                if (objSize <= 2 || objSize > 2000) { scanPos++; continue; }

                int typeCode = reader.readBitShort();
                if (typeCode < 0 || typeCode > 255) { scanPos++; continue; }

                String name = typeName(typeCode);
                System.out.printf("[%2d] @ 0x%x: MS=%4d, type=%3d (0x%02X) = %s%n",
                    i, scanPos, objSize, typeCode, typeCode, name);

                scanPos += 2 + objSize;
            } catch (Exception e) {
                scanPos++;
            }
        }
    }

    static String typeName(int code) {
        if (code < 0) return "INVALID";
        switch(code) {
            case 0: return "UNUSED";
            case 1: return "TEXT";
            case 2: return "ATTDEF";
            case 3: return "ATTRIB";
            case 4: return "SEQEND";
            case 5: return "ENDBLK";
            case 7: return "INSERT";
            case 8: return "MINSERT";
            case 10: return "VERTEX_2D";
            case 11: return "VERTEX_3D";
            case 13: return "VERTEX_MESH";
            case 14: return "VERTEX_PFACE";
            case 15: return "VERTEX_PFACE_FACE";
            case 16: return "POLYLINE_2D";
            case 17: return "POLYLINE_3D";
            case 18: return "ARC";
            case 19: return "CIRCLE";
            case 20: return "LINE";
            case 21: return "DIMENSION_ORDINATE";
            case 22: return "DIMENSION_LINEAR";
            case 23: return "DIMENSION_ALIGNED";
            case 24: return "DIMENSION_ANGULAR_3PT";
            case 25: return "DIMENSION_ANGULAR_2LN";
            case 26: return "DIMENSION_RADIUS";
            case 27: return "DIMENSION_DIAMETER";
            case 28: return "POINT";
            case 29: return "FACE_3D";
            case 30: return "POLYLINE_PFACE";
            case 31: return "POLYLINE_MESH";
            case 32: return "SOLID";
            case 33: return "TRACE";
            case 34: return "SHAPE";
            case 35: return "VIEWPORT";
            case 36: return "ELLIPSE";
            case 37: return "SPLINE";
            case 38: return "REGION";
            case 39: return "SOLID_3D";
            case 40: return "BODY";
            case 41: return "RAY";
            case 42: return "XLINE";
            case 43: return "DICTIONARY";
            case 45: return "MTEXT";
            case 46: return "LEADER";
            case 47: return "TOL";
            case 48: return "BLOCK_HEADER";
            case 49: return "BLOCK_END";
            case 50: return "LTYPE";
            case 51: return "LAYER";
            case 52: return "STYLE";
            case 53: return "STYLE_ALTERNATE";
            case 54: return "VIEW";
            case 55: return "UCS";
            case 56: return "VPORT";
            case 57: return "APPID";
            case 58: return "DIMSTYLE";
            case 59: return "DIMSTYLE_ALTERNATE";
            case 62: return "LAYOUT";
            case 65: return "GROUP";
            case 67: return "APPID_ALTERNATE";
            case 69: return "DIMSTYLE_ALTERNATE";
            case 75: return "LWPLINE";
            case 76: return "HATCH";
            case 77: return "XRECORD";
            case 78: return "PLACEHOLDER";
            case 79: return "VBA_PROJECT";
            case 80: return "LAYOUT";
            default: return "TYPE_" + code;
        }
    }
}
