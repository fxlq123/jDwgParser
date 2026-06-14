import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class ScanSimple {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        
        int pos = 0x52b9;
        int count = 0;
        int blockHeaders = 0;
        int inserts = 0;
        List<Integer> bhOffsets = new ArrayList<>();
        List<Integer> insOffsets = new ArrayList<>();
        Map<Integer, Integer> typeCounts = new HashMap<>();
        
        System.out.println("从 0x52b9 扫描对象 (前 50 个):\n");

        while (pos < data.length - 8 && count < 500) {
            int lo = data[pos] & 0xFF;
            int hi = data[pos+1] & 0xFF;
            int w = lo | (hi << 8);
            int ms = w & 0x7FFF;
            if ((w & 0x8000) != 0) {
                int lo2 = data[pos+2] & 0xFF;
                int hi2 = data[pos+3] & 0xFF;
                ms |= ((lo2 | (hi2 << 8)) & 0x7FFF) << 15;
            }
            
            int msBytes = (w & 0x8000) != 0 ? 4 : 2;
            
            if (ms < 2 || ms > 8192) {
                System.out.println("停止 @ pos=0x" + Integer.toHexString(pos) + " size=" + ms);
                break;
            }

            int objStart = pos;
            int ti = pos + msBytes;
            int b2 = data[ti] & 0xFF;
            int b3 = (ti + 1 < data.length) ? data[ti+1] & 0xFF : 0;
            int b4 = (ti + 2 < data.length) ? data[ti+2] & 0xFF : 0;
            
            int opcode = (b2 >> 6) & 3;
            int type = 0;
            
            if (opcode == 0) {
                int v = 0;
                for (int i = 2; i < 8; i++) v = (v << 1) | bitAt(b2, i);
                for (int i = 0; i < 8; i++) v = (v << 1) | bitAt(b3, i);
                for (int i = 0; i < 2; i++) v = (v << 1) | bitAt(b4, i);
                type = v;
            } else if (opcode == 1) {
                int v = 0;
                for (int i = 2; i < 8; i++) v = (v << 1) | bitAt(b2, i);
                for (int i = 0; i < 2; i++) v = (v << 1) | bitAt(b3, i);
                type = v;
            } else if (opcode == 2) {
                type = 0;
            } else {
                type = 256;
            }

            count++;
            typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);
            
            if (type == 0x30) { blockHeaders++; bhOffsets.add(objStart); }
            if (type == 0x07) { inserts++; insOffsets.add(objStart); }

            if (count <= 50 || type == 0x30 || type == 0x07) {
                String text = extractText(data, ti, Math.min(ms, 128));
                System.out.printf("#%3d @0x%x MS=%d type=0x%02x(%d) %s%n",
                    count, objStart, ms, type, type, 
                    (type == 0x30 ? "BLOCK_HEADER" : type == 0x07 ? "INSERT" : getTypeName(type)) 
                    + (text.isEmpty() ? "" : " '" + text + "'"));
            }

            pos = objStart + msBytes + ms;
        }
        
        System.out.println("\n共 " + count + " 个对象");
        System.out.println("BLOCK_HEADER: " + blockHeaders + " 个, INSERT: " + inserts + " 个");
        
        System.out.println("\n类型统计:");
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeCounts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<Integer, Integer> e : sorted) {
            System.out.println("  0x" + String.format("%02x", e.getKey()) + " (" + e.getKey() + "): " + e.getValue() + " " + getTypeName(e.getKey()));
        }
        
        // 现在让我们聚焦于 BLOCK_HEADER 和 INSERT:
        System.out.println("\n=== BLOCK_HEADER 详细信息 ===");
        for (int off : bhOffsets) {
            System.out.println("BLOCK_HEADER @0x" + Integer.toHexString(off));
            // 在对象数据中找文本
            String name = findTextNear(data, off, 128);
            System.out.println("  Name: '" + name + "'");
        }
        
        System.out.println("\n=== INSERT 详细信息 ===");
        for (int off : insOffsets) {
            System.out.println("INSERT @0x" + Integer.toHexString(off));
        }
    }
    
    static int bitAt(int byteVal, int pos) {
        return (byteVal >> (7 - pos)) & 1;
    }

    static String getTypeName(int type) {
        switch (type) {
            case 0x01: return "TEXT";
            case 0x07: return "INSERT";
            case 0x0F: return "CIRCLE";
            case 0x1F: return "LWPOLYLINE";
            case 0x30: return "BLOCK_HEADER";
            case 0x43: return "LAYER";
            default: return "type_" + type;
        }
    }

    static String extractText(byte[] data, int start, int maxSearch) {
        StringBuilder sb = new StringBuilder();
        // 跳过一些字节以避免 type 和 entity header 数据
        int searchStart = start + 8;
        int end = Math.min(searchStart + Math.max(maxSearch, 16), data.length);
        for (int i = searchStart; i < end; i++) {
            int c = data[i] & 0xFF;
            if (c >= 32 && c < 127) {
                sb.append((char)c);
                if (sb.length() > 32) break;
            } else if (sb.length() > 2) {
                break;
            } else {
                sb.setLength(0);
            }
        }
        return sb.toString().trim();
    }
    
    static String findTextNear(byte[] data, int start, int maxLen) {
        // 直接在对象数据中查找文本
        int end = Math.min(start + maxLen + 32, data.length);
        StringBuilder longest = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (int i = start + 8; i < end; i++) {
            int c = data[i] & 0xFF;
            if (c >= 32 && c < 127) {
                current.append((char)c);
                if (current.length() > longest.length() && current.length() > 3) {
                    longest.setLength(0);
                    longest.append(current);
                }
            } else {
                if (current.length() > longest.length()) {
                    longest.setLength(0);
                    longest.append(current);
                }
                current.setLength(0);
            }
        }
        return longest.toString();
    }
}
