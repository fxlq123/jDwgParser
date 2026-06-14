import io.dwg.core.io.*;
import io.dwg.core.version.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class DebugBitReader {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        
        // Object at 0x52b9: MS = 114, object data at 0x52bb
        int objStart = 0x52bb;
        int objSize = 114;
        
        System.out.println("Object data at 0x52bb (first 16 bytes):");
        for (int i = 0; i < Math.min(16, objSize); i++) {
            int b = data[objStart + i] & 0xFF;
            System.out.print(String.format("%02x(%s) ", b, Integer.toBinaryString(b)));
        }
        System.out.println();
        
        // Create BitStreamReader from these bytes
        byte[] objBytes = new byte[objSize];
        System.arraycopy(data, objStart, objBytes, 0, objSize);
        
        ByteBuffer bb = ByteBuffer.wrap(objBytes);
        ByteBufferBitInput bbi = new ByteBufferBitInput(bb);
        BitStreamReader reader = new BitStreamReader(bbi, DwgVersion.R2000);
        
        // Read first 2 bits (opcode for BS)
        int opcode = bbi.readBits(2);
        System.out.println("Opcode (first 2 bits): " + opcode + " = " + Integer.toBinaryString(opcode));
        System.out.println("After opcode, bit position: " + bbi.position());
        
        // Try reading bits manually
        bbi.seek(0);
        System.out.print("Bit by bit: ");
        for (int i = 0; i < 16; i++) {
            int bit = bbi.readBits(1);
            System.out.print(bit);
            if (i == 1 || i == 9) System.out.print(" ");
        }
        System.out.println();
        
        // Now use readBitShort
        bbi.seek(0);
        BitStreamReader reader2 = new BitStreamReader(bbi, DwgVersion.R2000);
        int typeCode = reader2.readBitShort();
        System.out.println("Type code from readBitShort: 0x" + Integer.toHexString(typeCode) + " (" + typeCode + ")");
        
        // Try with different slice: read from 0x52bb directly as bytes
        ByteBuffer bb2 = ByteBuffer.wrap(data, objStart, objSize);
        ByteBufferBitInput bbi2 = new ByteBufferBitInput(bb2);
        BitStreamReader reader3 = new BitStreamReader(bbi2, DwgVersion.R2000);
        int typeCode2 = reader3.readBitShort();
        System.out.println("Type code (slice wrap): 0x" + Integer.toHexString(typeCode2) + " (" + typeCode2 + ")");
    }
}
