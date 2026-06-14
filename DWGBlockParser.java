import io.dwg.core.io.*;
import io.dwg.core.version.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * DWG 블록 파서 - 최종 버전
 * R2000 (AC1015) 형식의 DWG 파일에서 BLOCK_HEADER와 INSERT 객체를 추출
 */
public class DWGBlockParser {

    // ========== 엔티티 헬퍼 클래스 ==========
    static class BlockDef {
        long handle;
        String name;
        int flags;
        double[] basePoint;
        int insertCount = 0;
        
        public String toString() {
            return String.format("BLOCK handle=0x%x name='%s' refs=%d", handle, name, insertCount);
        }
    }

    static class InsertRef {
        long handle;
        long blockHeaderHandle;
        double[] insertionPoint;
    }

    // ========== 모듈러 바이트 읽기 ==========
    static long readMC(byte[] data, int[] posRef) {
        int idx = posRef[0];
        long result = 0;
        int count = 0;
        while (true) {
            int b = data[idx] & 0xFF;
            idx++;
            count++;
            result = (result << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) break;
            if (count > 5) break;
        }
        posRef[0] = idx;
        return result;
    }

    static int readMS16(byte[] data, int[] posRef) {
        int idx = posRef[0];
        int result = 0;
        int shift = 0;
        while (true) {
            int lo = data[idx] & 0xFF;
            int hi = data[idx+1] & 0xFF;
            int w = lo | (hi << 8);
            result |= (w & 0x7FFF) << shift;
            idx += 2;
            if ((w & 0x8000) == 0) break;
            shift += 15;
        }
        posRef[0] = idx;
        return result;
    }

    // ========== Handles Section 파싱 ==========
    static class HandleEntry {
        long handle;
        long offset;
        HandleEntry(long h, long o) { handle = h; offset = o; }
    }

    static List<HandleEntry> parseHandlesSection(byte[] data) {
        // Handles section 시작점 탐색
        // 객체가 0x52b9 부터 시작하고, 839개의 handle entry 존재
        // 섹션은 객체 영역보다 앞에 위치
        
        int start = findHandlesSectionStart(data);
        if (start < 0) return Collections.emptyList();
        
        System.out.println("Handles section starts at: 0x" + Integer.toHexString(start));
        
        List<HandleEntry> entries = new ArrayList<>();
        int[] pos = {start + 6};  // skip 6-byte header (num, size, CRC in RS_BE)
        
        long handle = 0;
        long offset = 0;
        int safety = 5000;
        while (pos[0] < data.length - 4 && safety-- > 0) {
            long hdelta = readMC(data, pos);
            long odelta = readMC(data, pos);
            if (hdelta < 0 || odelta < 0) break;
            if (hdelta == 0 && odelta == 0) continue;
            handle += hdelta;
            offset += odelta;
            if (offset < data.length && handle > 0) {
                entries.add(new HandleEntry(handle, offset));
            }
            if (hdelta == 0 && odelta == 0) break;
        }
        
        return entries;
    }

    static int findHandlesSectionStart(byte[] data) {
        // 다양한 시작점을 시도하여 handles section 찾기
        int[] candidates = {0x4f41, 0x4f4a, 0x4f51, 0x4f60, 0x4f70, 0x4f80, 0x4f90, 0x4fa0, 0x4fb0, 0x4fc0};
        
        int bestStart = -1;
        int bestCount = 0;
        
        for (int start : candidates) {
            try {
                List<HandleEntry> entries = tryParseHandles(data, start);
                if (entries.size() > bestCount && isValidHandles(entries)) {
                    bestCount = entries.size();
                    bestStart = start;
                }
            } catch (Exception e) {}
        }
        
        return bestStart;
    }

    static List<HandleEntry> tryParseHandles(byte[] data, int startOffset) {
        List<HandleEntry> entries = new ArrayList<>();
        int[] pos = {startOffset + 6};  // skip 6-byte header
        
        long handle = 0;
        long offset = 0;
        int safety = 5000;
        while (pos[0] < data.length - 4 && safety-- > 0) {
            long hdelta = readMC(data, pos);
            long odelta = readMC(data, pos);
            if (hdelta < 0 || odelta < 0) break;
            handle += hdelta;
            offset += odelta;
            if (handle > 0 && offset < data.length) {
                entries.add(new HandleEntry(handle, offset));
            }
            if (hdelta == 0 && odelta == 0 && entries.size() > 10) break;
        }
        return entries;
    }

    static boolean isValidHandles(List<HandleEntry> entries) {
        if (entries.size() < 50 || entries.size() > 5000) return false;
        // 첫 번째 handle은 보통 0x1 또는 1
        // offset은 0x5000 이후여야 함
        HandleEntry first = entries.get(0);
        return first.handle <= 10 && first.offset > 0x5000 && first.offset < 0x8000;
    }

    // ========== 객체 파싱 ==========
    static BitStreamReader makeReader(byte[] data, int offset, int length) {
        // 중요: 바이트 배열 복사본을 사용해야 함 (ByteBuffer.wrap with offset 버그 방지)
        byte[] slice = new byte[length];
        System.arraycopy(data, offset, slice, 0, length);
        ByteBuffer bb = ByteBuffer.wrap(slice);
        return new BitStreamReader(new ByteBufferBitInput(bb), DwgVersion.R2000);
    }

    static Object parseObjectAt(byte[] data, long offset,
                                 List<BlockDef> blocks,
                                 List<InsertRef> inserts,
                                 Map<Integer, Integer> typeCounts) {
        if (offset < 0 || offset >= data.length - 4) return null;
        
        int[] pos = {(int)offset};
        try {
            int ms = readMS16(data, pos);
            int dataStart = pos[0];
            int objectEnd = dataStart + ms;
            
            if (ms <= 0 || ms > 20000 || objectEnd > data.length) {
                return null;
            }

            BitStreamReader reader = makeReader(data, dataStart, ms);
            int typeCode = reader.readBitShort();
            
            typeCounts.merge(typeCode, 1, Integer::sum);
            
            if (typeCode == 0x30) {  // BLOCK_HEADER
                BlockDef block = parseBlockHeader(data, dataStart, ms);
                if (block != null) blocks.add(block);
                return block;
            } else if (typeCode == 0x07) {  // INSERT
                InsertRef ins = parseInsert(data, dataStart, ms);
                if (ins != null) inserts.add(ins);
                return ins;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static BlockDef parseBlockHeader(byte[] data, int offset, int length) {
        try {
            BitStreamReader reader = makeReader(data, offset, length);
            
            // Type code (BS)
            int typeCode = reader.readBitShort();
            if (typeCode != 0x30) return null;
            
            BlockDef block = new BlockDef();
            
            // bitsize (RL - raw 32 bits / 4 bytes)
            reader.getInput().readBits(32);  // skip
            
            // object handle (H)
            block.handle = reader.readHandle();
            
            // EED (BS size, if non-zero then skip data)
            int eedSize = reader.readBitShort();
            if (eedSize > 0 && eedSize < 4096) {
                try {
                    // handle byte + eedSize bytes of data
                    int bitsToSkip = 8 + eedSize * 8;
                    reader.getInput().readBits(bitsToSkip);
                } catch (Exception e) {}
            }
            
            // Common entity data (R2000)
            readCommonEntityData(reader);
            
            // Type-specific: block_name (T / text)
            block.name = reader.readText();
            
            // flags (BS)
            block.flags = reader.readBitShort();
            
            // base_point (3RD)
            try {
                block.basePoint = reader.read3RawDouble();
            } catch (Exception e) {
                block.basePoint = new double[]{0, 0, 0};
            }
            
            // xref_path (T)
            try {
                reader.readText();  // skip xref path
            } catch (Exception e) {}
            
            if (block.name == null || block.name.trim().isEmpty()) {
                block.name = "(empty)";
            }
            
            return block;
        } catch (Exception e) {
            return null;
        }
    }

    static InsertRef parseInsert(byte[] data, int offset, int length) {
        try {
            BitStreamReader reader = makeReader(data, offset, length);
            
            // Type code (BS)
            int typeCode = reader.readBitShort();
            if (typeCode != 0x07) return null;
            
            InsertRef ins = new InsertRef();
            
            // bitsize (RL - 32 bits)
            reader.getInput().readBits(32);
            
            // object handle (H)
            ins.handle = reader.readHandle();
            
            // EED
            int eedSize = reader.readBitShort();
            if (eedSize > 0 && eedSize < 4096) {
                try {
                    reader.getInput().readBits(8 + eedSize * 8);
                } catch (Exception e) {}
            }
            
            // Common entity data (R2000)
            readCommonEntityData(reader);
            
            // insertion_point (3BD)
            try {
                ins.insertionPoint = reader.read3BitDouble();
            } catch (Exception e) {
                ins.insertionPoint = new double[]{0, 0, 0};
            }
            
            // scale factors - 2 bit opcode
            int scaleOpcode = reader.getInput().readBits(2);
            if (scaleOpcode == 3) {
                // default scale 1,1,1 - no data
            } else if (scaleOpcode == 1) {
                // one raw double for all three
                reader.readRawDouble();
            } else {
                // three bit doubles
                reader.readBitDouble();
                reader.readBitDouble();
                reader.readBitDouble();
            }
            
            // rotation angle (BD)
            try { reader.readBitDouble(); } catch (Exception e) {}
            
            // extrusion direction (BD)
            try { reader.readBitDouble(); } catch (Exception e) {}
            
            // has_attributes (B)
            boolean hasAttrs = reader.getInput().readBit();
            
            // block_header_handle (H)
            ins.blockHeaderHandle = reader.readHandle();
            
            if (hasAttrs) {
                try {
                    reader.readHandle();  // first attrib
                    reader.readHandle();  // last attrib
                    reader.readHandle();  // seqend
                } catch (Exception e) {}
            }
            
            return ins;
        } catch (Exception e) {
            return null;
        }
    }

    static void readCommonEntityData(BitStreamReader reader) throws Exception {
        BitInput input = reader.getInput();
        
        // preview_exists (B)
        boolean previewExists = input.readBit();
        if (previewExists) {
            // preview_size (RL = 32 bits)
            int previewSize = 0;
            for (int i = 0; i < 4; i++) {
                previewSize |= (input.readBits(8) & 0xFF) << (i * 8);
            }
            if (previewSize > 0 && previewSize < 100000) {
                for (int i = 0; i < previewSize; i++) {
                    input.readBits(8);
                }
            }
        }
        
        // entmode (BB = 2 bits)
        input.readBits(2);
        
        // num_reactors (BL)
        int numReactors = reader.readBitLong();
        for (int i = 0; i < numReactors; i++) {
            reader.readHandle();
        }
        
        // nolinks (B) - not present, actually this is: xdic_missing_flag (B)
        // Skip xdic_missing_flag if present
        // Actually let me look: after num_reactors we have:
        
        // color (BS)
        reader.readBitShort();
        
        // ltype_scale (BD)
        reader.readBitDouble();
        
        // ltype_flags (BB) + plotstyle_flags (BB)
        input.readBits(2);
        input.readBits(2);
        
        // invisible (BS)
        reader.readBitShort();
        
        // linewt (RC = 8 bits)
        input.readBits(8);
    }

    // ========== 메인 함수 ==========
    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = Files.readAllBytes(Paths.get(filename));
        
        System.out.println("=== DWG 파일 분석 ===");
        System.out.println("파일: " + filename);
        System.out.println("크기: " + data.length + " bytes");
        System.out.println();
        
        // 1. Handles section 파싱
        List<HandleEntry> handles = parseHandlesSection(data);
        System.out.println("=== Handles Section ===");
        System.out.println("총 handle 수: " + handles.size());
        if (!handles.isEmpty()) {
            for (int i = 0; i < Math.min(5, handles.size()); i++) {
                HandleEntry e = handles.get(i);
                System.out.println("  [" + i + "] handle=0x" + Long.toHexString(e.handle) +
                    " offset=0x" + Long.toHexString(e.offset));
            }
            System.out.println("  ...");
        }
        System.out.println();
        
        // 2. 각 객체 파싱
        List<BlockDef> blocks = new ArrayList<>();
        List<InsertRef> inserts = new ArrayList<>();
        Map<Integer, Integer> typeCounts = new HashMap<>();
        int parsedObjects = 0;
        
        for (HandleEntry entry : handles) {
            Object result = parseObjectAt(data, entry.offset, blocks, inserts, typeCounts);
            if (result != null) parsedObjects++;
        }
        
        // 3. INSERT -> BLOCK 연결
        Map<Long, BlockDef> handleToBlock = new HashMap<>();
        for (BlockDef b : blocks) {
            handleToBlock.put(b.handle, b);
        }
        for (InsertRef ins : inserts) {
            BlockDef b = handleToBlock.get(ins.blockHeaderHandle);
            if (b != null) {
                b.insertCount++;
            }
        }
        
        // 4. 결과 출력
        System.out.println("=== 객체 타입 통계 ===");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeCounts.entrySet());
        sortedTypes.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<Integer, Integer> e : sortedTypes) {
            String typeName = getTypeName(e.getKey());
            System.out.println(String.format("  Type 0x%02x (%3d) [%s]: %d개",
                e.getKey(), e.getKey(), typeName, e.getValue()));
        }
        System.out.println();
        
        System.out.println("=== BLOCK_HEADER (블록 정의) ===");
        System.out.println("총 " + blocks.size() + " 개 블록 정의");
        for (BlockDef b : blocks) {
            System.out.println(String.format("  Handle=0x%x  Name='%s'  참조회수=%d",
                b.handle, b.name, b.insertCount));
        }
        System.out.println();
        
        System.out.println("=== INSERT (블록 참조) ===");
        System.out.println("총 " + inserts.size() + " 개 INSERT");
        Map<String, Integer> refCounts = new TreeMap<>();
        for (InsertRef ins : inserts) {
            BlockDef b = handleToBlock.get(ins.blockHeaderHandle);
            String blockName = (b != null) ? b.name : 
                ("(알수없음: handle=0x" + Long.toHexString(ins.blockHeaderHandle) + ")");
            refCounts.merge(blockName, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : refCounts.entrySet()) {
            System.out.println(String.format("  '%s': %d회 참조", e.getKey(), e.getValue()));
        }
        System.out.println();
        
        // INSERT 목록 일부 출력
        System.out.println("=== INSERT 상세 (처음 10개) ===");
        for (int i = 0; i < Math.min(10, inserts.size()); i++) {
            InsertRef ins = inserts.get(i);
            BlockDef b = handleToBlock.get(ins.blockHeaderHandle);
            String blockName = (b != null) ? b.name : 
                "(handle=0x" + Long.toHexString(ins.blockHeaderHandle) + ")";
            System.out.println(String.format("  [%d] INSERT handle=0x%x block='%s' pos=(%.2f,%.2f,%.2f)",
                i, ins.handle, blockName,
                ins.insertionPoint[0], ins.insertionPoint[1], ins.insertionPoint[2]));
        }
    }

    static String getTypeName(int typeCode) {
        switch (typeCode) {
            case 0x01: return "TEXT";
            case 0x02: return "LINE";
            case 0x03: return "CIRCLE";
            case 0x04: return "ARC";
            case 0x05: return "LWPOLYLINE";
            case 0x06: return "LWPOLYLINE2";
            case 0x07: return "INSERT";
            case 0x08: return "ATTRIB";
            case 0x09: return "SEQEND";
            case 0x0A: return "ELLIPSE";
            case 0x15: return "SPLINE";
            case 0x1F: return "MTEXT";
            case 0x20: return "MTEXT2";
            case 0x30: return "BLOCK_HEADER";
            case 0x31: return "BLOCK";
            case 0x32: return "LAYER";
            case 0x33: return "LAYER_INDEX";
            case 0x34: return "LAYER_INDEX2";
            default: return "UNKNOWN";
        }
    }
}
