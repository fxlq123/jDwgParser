import io.dwg.core.io.*;
import io.dwg.core.version.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * R2000 블록 파서 - BLOCK_HEADER와 INSERT 객체를 분석
 */
public class BlockParserFinal {

    // 간단한 블록 정의 클래스
    static class BlockDef {
        long handle;
        String name;
        int flags;
        double[] basePoint;
        int insertCount;
    }

    // 간단한 INSERT 참조 클래스
    static class InsertRef {
        long handle;
        long blockHeaderHandle;
        double[] insertionPoint;
    }

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        
        List<BlockDef> blocks = new ArrayList<>();
        List<InsertRef> inserts = new ArrayList<>();
        Map<Integer, Integer> typeCounts = new HashMap<>();

        // 객체 시작 위치 (handles section 분석 결과 기반)
        int pos = 0x52b9;
        int objectNum = 0;
        int errors = 0;

        System.out.println("=== R2000 DWG 블록 분석 ===\n");
        System.out.println("파일 크기: " + data.length + " bytes");
        System.out.println("객체 시작 위치: 0x" + Integer.toHexString(pos));
        System.out.println();

        while (pos < data.length - 16) {
            try {
                ParseResult result = parseObject(data, pos, objectNum, blocks, inserts, typeCounts);
                if (result != null && result.nextOffset > pos) {
                    pos = result.nextOffset;
                    objectNum++;
                } else {
                    // 잘못된 객체 - 바이트 이동
                    pos++;
                    errors++;
                    if (errors > 200) break;
                }
            } catch (Exception e) {
                pos++;
                errors++;
                if (errors > 200) break;
            }
        }

        // 결과 출력
        System.out.println("\n=== 분석 결과 ===");
        System.out.println("총 객체 수: " + objectNum);
        System.out.println("BLOCK_HEADER 수: " + blocks.size());
        System.out.println("INSERT 수: " + inserts.size());
        
        // BLOCK_HEADER별 INSERT 참조 수 카운트
        Map<Long, BlockDef> handleToBlock = new HashMap<>();
        for (BlockDef b : blocks) {
            handleToBlock.put(b.handle, b);
        }
        
        // INSERT에서 block_header_handle로 블록 참조 연결
        for (InsertRef ins : inserts) {
            BlockDef b = handleToBlock.get(ins.blockHeaderHandle);
            if (b != null) {
                b.insertCount++;
            }
        }

        System.out.println("\n=== 블록 정의 (BLOCK_HEADER) ===");
        if (blocks.isEmpty()) {
            System.out.println("(BLOCK_HEADER를 찾지 못했습니다)");
        }
        for (BlockDef b : blocks) {
            System.out.printf("  Handle: 0x%X, Name: '%s', Flags: 0x%02X, Refs: %d%n",
                b.handle, b.name, b.flags, b.insertCount);
        }

        // 이름으로 통계
        Map<String, Integer> nameCount = new TreeMap<>();
        for (BlockDef b : blocks) {
            nameCount.merge(b.name, 1, Integer::sum);
        }

        System.out.println("\n=== 블록 참조 통계 ===");
        if (inserts.isEmpty()) {
            System.out.println("(INSERT를 찾지 못했습니다)");
        }
        
        // block_header_handle 기반으로 참조 수 보기
        Map<Long, Integer> handleRefCount = new HashMap<>();
        for (InsertRef ins : inserts) {
            handleRefCount.merge(ins.blockHeaderHandle, 1, Integer::sum);
        }
        
        for (Map.Entry<Long, Integer> e : handleRefCount.entrySet()) {
            BlockDef b = handleToBlock.get(e.getKey());
            String name = (b != null) ? b.name : "(참조 실패: handle=0x" + Long.toHexString(e.getKey()) + ")";
            System.out.printf("  '%s': %d 회 참조%n", name, e.getValue());
        }

        // 타입 통계
        System.out.println("\n=== 객체 타입 통계 ===");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeCounts.entrySet());
        sortedTypes.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<Integer, Integer> e : sortedTypes) {
            System.out.printf("  Type 0x%02X (%d): %d 개%n", e.getKey(), e.getKey(), e.getValue());
        }
    }

    static class ParseResult {
        int nextOffset;
        ParseResult(int next) { this.nextOffset = next; }
    }

    static ParseResult parseObject(byte[] data, int pos, int objNum,
                                    List<BlockDef> blocks,
                                    List<InsertRef> inserts,
                                    Map<Integer, Integer> typeCounts) {
        // 객체가 MS(크기) + 데이터 형태로 구성됨
        // MS: low byte, high byte (LE 16-bit). bit 15 = 1이면 계속 읽음
        int lo = data[pos] & 0xFF;
        int hi = data[pos + 1] & 0xFF;
        int ms = lo | (hi << 8);
        if (ms < 2 || ms > 8192) return null;
        
        // 다음 객체로의 계산: MS가 16-bit (2바이트) 이후 데이터가 ms 바이트
        int objDataStart = pos + 2;
        int nextObjPos = objDataStart + ms;
        if (nextObjPos > data.length) return null;

        // BitStreamReader 생성 (objDataStart에서 시작)
        ByteBufferBitInput input = new ByteBufferBitInput(
            ByteBuffer.wrap(data, objDataStart, ms));
        BitStreamReader reader = new BitStreamReader(input, DwgVersion.R2000);

        // BS (type code)
        int typeCode = reader.readBitShort();
        if (typeCode < 0 || typeCode > 200) return null;

        typeCounts.merge(typeCode, 1, Integer::sum);

        try {
            if (typeCode == 0x30) {
                // BLOCK_HEADER
                BlockDef block = parseBlockHeader(reader);
                if (block != null) {
                    blocks.add(block);
                    if (objNum < 10) System.out.printf("  [%d] BLOCK_HEADER @0x%X handle=0x%X name='%s'%n",
                        objNum, pos, block.handle, block.name);
                }
            } else if (typeCode == 0x07) {
                // INSERT
                InsertRef ins = parseInsert(reader);
                if (ins != null) {
                    inserts.add(ins);
                    if (objNum < 10) System.out.printf("  [%d] INSERT @0x%X handle=0x%X block=0x%X%n",
                        objNum, pos, ins.handle, ins.blockHeaderHandle);
                }
            }
            // 다른 타입: 무시 (파싱 오류가 발생해도 계속 진행)
        } catch (Exception e) {
            // 파싱 오류 - 조용히 건너뜀
        }

        return new ParseResult(nextObjPos);
    }

    static BlockDef parseBlockHeader(BitStreamReader reader) throws Exception {
        // Entity header: bitsize (RL) + object_handle (H) + EED
        // bitsize (RL): 32 bits, 4 bytes
        reader.getInput().readBits(32); // skip bitsize

        // object handle (H)
        long objectHandle = reader.readHandle();

        // EED: BS size. If non-zero, skip additional bytes
        int eedSize = reader.readBitShort();
        if (eedSize > 0 && eedSize < 4096) {
            // skip the handle and data bytes
            try {
                reader.getInput().readBits(8); // first byte of handle
                int rest = eedSize * 8;
                reader.getInput().readBits(rest);
            } catch (Exception e) { /* ignore */ }
        }

        // Common entity data (R2000)
        readCommonEntityDataR2000(reader);

        BlockDef b = new BlockDef();
        b.handle = objectHandle;

        // Type-specific: block_name (TV/T), flags (BS), base_point (3RD), xref_path (TV/T)
        // block_name
        b.name = reader.readText();
        // flags (BS)
        b.flags = reader.readBitShort();
        // base_point (3RD) - 3 raw doubles = 24 bytes
        try {
            b.basePoint = reader.read3RawDouble();
        } catch (Exception e) {
            b.basePoint = new double[]{0, 0, 0};
        }
        // xref_path (TV/T) - may be empty/0-length
        try {
            reader.readText(); // skip xref
        } catch (Exception e) { /* ignore */ }

        if (b.name == null || b.name.trim().isEmpty()) {
            b.name = "(empty)";
        }

        return b;
    }

    static InsertRef parseInsert(BitStreamReader reader) throws Exception {
        // Entity header: bitsize (RL) + object_handle (H) + EED
        reader.getInput().readBits(32); // skip bitsize

        long objectHandle = reader.readHandle();

        int eedSize = reader.readBitShort();
        if (eedSize > 0 && eedSize < 4096) {
            try {
                reader.getInput().readBits(eedSize * 8 + 8); // handle byte + data
            } catch (Exception e) { /* ignore */ }
        }

        // Common entity data (R2000)
        readCommonEntityDataR2000(reader);

        InsertRef ins = new InsertRef();
        ins.handle = objectHandle;

        // insertion_point (3BD)
        try {
            ins.insertionPoint = reader.read3BitDouble();
        } catch (Exception e) {
            ins.insertionPoint = new double[]{0, 0, 0};
        }

        // scale factors: in R2000 (pre R14 condition): opcode-based scale
        int scaleFlags = reader.getInput().readBits(2);
        if (scaleFlags == 3) {
            // x=y=z=1.0, no data
        } else if (scaleFlags == 1) {
            // one raw double for all three
            reader.readRawDouble();
        } else {
            // three bit doubles
            reader.readBitDouble();
            reader.readBitDouble();
            reader.readBitDouble();
        }

        // rotation (BD)
        try { reader.readBitDouble(); } catch (Exception e) {}

        // extrusion (BD)
        try { reader.readBitDouble(); } catch (Exception e) {}

        // has_attribs (B)
        boolean hasAttribs = reader.getInput().readBit();

        // block_header_handle (H)
        ins.blockHeaderHandle = reader.readHandle();

        if (hasAttribs) {
            try {
                reader.readHandle(); // first attrib
                reader.readHandle(); // last attrib
                reader.readHandle(); // seqend
            } catch (Exception e) { /* ignore */ }
        }

        return ins;
    }

    // R2000 common entity data (정확히 스킵하기 위한 함수)
    static void readCommonEntityDataR2000(BitStreamReader reader) throws Exception {
        BitInput input = reader.getInput();
        
        // 1. preview_exists (B)
        boolean previewExists = input.readBit();
        if (previewExists) {
            long previewSize = 0;
            for (int i = 0; i < 4; i++) {
                previewSize |= (long)(input.readBits(8) & 0xFF) << (i * 8);
            }
            if (previewSize > 0 && previewSize < 1000000) {
                for (int i = 0; i < previewSize; i++) {
                    input.readBits(8);
                }
            }
        }

        // 2. entmode (BB = 2 bits)
        input.readBits(2);

        // 3. num_reactors (BL)
        int numReactors = reader.readBitLong();

        // 4. nolinks (B) - only if no reactors? Actually separate bit
        // For R2000: skip nolinks bit if numReactors==0? Actually: there's a nolinks bit
        // Let's check: if numReactors > 0, read those handles
        for (int i = 0; i < numReactors; i++) {
            reader.readHandle();
        }

        // Skip is_xdic_missing (B) for non-entities? Actually BLOCK_HEADER is not an entity
        // But in our flow we still need to skip
        // In R2000: is_xdic_missing (B) exists for entities
        // Actually: let's skip carefully with bit operations
        
        // 5. color (BS)
        reader.readBitShort();

        // 6. ltype_scale (BD)
        reader.readBitDouble();

        // 7. ltype_flags (BB) + plotstyle_flags (BB)
        input.readBits(2);
        input.readBits(2);

        // 8. invisible (BS)
        reader.readBitShort();

        // 9. linewt (RC) = 8 bits
        input.readBits(8);
    }
}
