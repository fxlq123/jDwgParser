package run;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;
import java.nio.charset.StandardCharsets;

/**
 * 在整个 DWG 文件中搜索已知的字符串（如 *Model_Space, 块名称等）
 */
public class SearchStringsInDwg {
    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println("文件大小: " + data.length + " bytes");
        System.out.println();

        // 搜索标准块名
        String[] knownNames = {
            "*Model_Space", "*Paper_Space", "*Paper_Space0",
            "Model", "Paper", "BLOCK", "INSERT",
        };

        for (String name : knownNames) {
            byte[] pattern = name.getBytes(StandardCharsets.US_ASCII);
            List<Integer> positions = searchBytes(data, pattern);
            if (!positions.isEmpty()) {
                System.out.println("'" + name + "' found at: ");
                for (int p : positions) {
                    System.out.printf("  0x%04x (+%d) -- context: ", p, p);
                    for (int i = Math.max(0, p - 4); i < Math.min(data.length, p + pattern.length + 8); i++) {
                        int b = data[i] & 0xFF;
                        char c = (b >= 32 && b < 127) ? (char)b : '.';
                        System.out.print(c);
                    }
                    System.out.println();
                }
                System.out.println();
            }
        }

        // 搜索中文（UTF-8）- 文件名中的 "拨杆座"
        String chineseText = "拨杆座";
        byte[] utf8Pattern = chineseText.getBytes(StandardCharsets.UTF_8);
        List<Integer> chinesePos = searchBytes(data, utf8Pattern);
        System.out.println("中文 '" + chineseText + "' 位置: " + chinesePos);

        // 搜索所有 >= 4 字节的可打印 ASCII 字符串
        System.out.println("\n=== 所有 >= 5 字符的可打印 ASCII 字符串（前 50 个）===\n");
        List<StringPos> strings = findAllStrings(data, 5);
        int shown = 0;
        for (StringPos sp : strings) {
            System.out.printf("  0x%05x (+%5d): '%s'%n", sp.pos, sp.pos, sp.text);
            if (++shown >= 50) break;
        }

        // 统计字符串总数
        System.out.println("\n  共找到 " + strings.size() + " 个 >= 5 字符的 ASCII 字符串");

        // 按出现频率排序
        System.out.println("\n=== 出现频率最高的字符串（前 30 个）===\n");
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (StringPos sp : strings) {
            freq.merge(sp.text, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sortedFreq = new ArrayList<>(freq.entrySet());
        sortedFreq.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        shown = 0;
        for (Map.Entry<String, Integer> e : sortedFreq) {
            System.out.printf("  '%-40s' : %d 次%n", e.getKey(), e.getValue());
            if (++shown >= 30) break;
        }

        // 搜索可能的块名称格式（如 "xxx_block" 等）
        System.out.println("\n=== 包含 block/model/paper 字样的字符串 ===");
        for (StringPos sp : strings) {
            String lower = sp.text.toLowerCase();
            if (lower.contains("block") || lower.contains("model") || lower.contains("paper") ||
                lower.contains("layer") || lower.contains("style") || lower.contains("line")) {
                System.out.printf("  0x%05x: '%s'%n", sp.pos, sp.text);
            }
        }

        // 找出最长的字符串
        System.out.println("\n=== 最长的字符串（前 20 个）===\n");
        strings.sort((a, b) -> b.text.length() - a.text.length());
        shown = 0;
        for (StringPos sp : strings) {
            System.out.printf("  0x%05x (len=%3d): '%s'%n", sp.pos, sp.text.length(), sp.text);
            if (++shown >= 20) break;
        }
    }

    static class StringPos {
        int pos;
        String text;
        StringPos(int p, String t) { pos = p; text = t; }
    }

    static List<Integer> searchBytes(byte[] data, byte[] pattern) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if ((data[i+j] & 0xFF) != (pattern[j] & 0xFF)) {
                    match = false;
                    break;
                }
            }
            if (match) result.add(i);
        }
        return result;
    }

    static List<StringPos> findAllStrings(byte[] data, int minLen) {
        List<StringPos> result = new ArrayList<>();
        int i = 0;
        while (i < data.length) {
            int j = i;
            StringBuilder sb = new StringBuilder();
            while (j < data.length) {
                int b = data[j] & 0xFF;
                if (b >= 32 && b <= 126) {
                    sb.append((char)b);
                    j++;
                } else {
                    break;
                }
            }
            if (sb.length() >= minLen) {
                result.add(new StringPos(i, sb.toString()));
            }
            if (j > i) i = j; else i++;
        }
        return result;
    }
}
