package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.*;

public class DumpR2018ObjectStream {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];
        
        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件大小: " + data.length + " bytes");
        System.out.println("头部版本: " + new String(data, 0, 6));
        System.out.println();
        
        // 找到 R2018 对象 section — 先尝试已知的对象位置
        // 我们直接扫描查找有效的 object header 模式
        
        // 1600 字节后开始扫描，跳过 header
        int start = 0x100;
        int found = 0;
        
        System.out.println("扫描有效对象头...");
        for (int off = start; off < Math.min(start + 50000, data.length - 16); off++) {
            ByteBufferBitInput in = new ByteBufferBitInput(data);
            in.seek((long) off * 8);
            BitStreamReader r = new BitStreamReader(in, DwgVersion.R2018);
            
            try {
                // 尝试读取 MS (Modular Short) — 对象大小
                int firstByte = data[off] & 0xFF;
                // MS 编码: 最高位标记是否继续，低7位是值
                // 但是我们先看 raw byte 是否合理
                
                // 尝试作为 R2010+ 对象来读取
                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0xFFFF) continue;
                
                // 接下来是 UMC (handlestream size)
                long umc = r.readUMC();
                if (umc < 0 || umc > 0xFFFF) continue;
                
                // 接下来是 BOT (Bit Object Type)
                int bb = in.readBits(2);
                int type;
                if (bb == 0) type = in.readBits(8) & 0xFF;
                else if (bb == 1) type = (in.readBits(8) & 0xFF) + 0x1F0;
                else type = (in.readBits(8) & 0xFF) | ((in.readBits(8) & 0xFF) << 8);
                
                if (type >= 0 && type <= 2000 && objSize > 20 && objSize < 2000) {
                    // 可能是有效的对象
                    long bitPos = in.position();
                    long bytePos = bitPos / 8;
                    
                    if (found < 20) {
                        System.out.println();
                        System.out.println("=== 潜在对象 @ 0x" + Long.toHexString(bytePos) + " ===");
                        System.out.println("  objSize: " + objSize + " bytes");
                        System.out.println("  handleStreamSize (UMC): " + umc);
                        System.out.println("  typeCode: " + type + " (0x" + Integer.toHexString(type) + ")");
                        
                        // dump 接下来 64 bytes 作为对象数据
                        int dataStart = (int) bytePos;
                        System.out.print("  数据: ");
                        for (int i = 0; i < Math.min(64, objSize); i++) {
                            if (dataStart + i < data.length) {
                                System.out.printf("%02X ", data[dataStart + i]);
                            }
                        }
                        System.out.println();
                        
                        // 尝试解释: numReactors (BL)
                        ByteBufferBitInput in2 = new ByteBufferBitInput(data);
                        in2.seek((long) dataStart * 8);
                        BitStreamReader r2 = new BitStreamReader(in2, DwgVersion.R2018);
                        try {
                            int nr = r2.readBitLong();
                            System.out.println("  [尝试解析] numReactors: " + nr);
                            boolean hasX = r2.getInput().readBit();
                            System.out.println("  [尝试解析] hasXDic: " + hasX);
                            long ownerH = r2.readHandle();
                            System.out.println("  [尝试解析] ownerHandle: 0x" + Long.toHexString(ownerH));
                        } catch (Exception e) {
                            System.out.println("  [尝试解析] 出错: " + e.getMessage());
                        }
                        found++;
                    }
                }
            } catch (Exception e) {
                continue;
            }
        }
        
        System.out.println("\n共找到 " + found + " 个潜在对象位置");
    }
}
