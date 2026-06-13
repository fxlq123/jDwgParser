package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.SectionInputStream;
import io.dwg.core.version.DwgVersion;
import io.dwg.core.version.DwgVersionDetector;
import io.dwg.format.common.DwgFileStructureHandler;
import io.dwg.format.common.DwgFileStructureHandlerFactory;
import io.dwg.format.r2007.R2007FileHeader;
import io.dwg.format.r2007.R2007FileStructureHandler;
import io.dwg.format.r2007.R2007PageMapParser;
import io.dwg.format.r2007.R2007SectionMapParser;
import io.dwg.format.r2007.R2007SystemPageReader;

import java.io.File;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

public class DebugR2007Handler {
    public static void main(String[] args) throws Exception {
        String[] files = {
            "samples/2018/Dynblocks.dwg",
            "samples/2007/circle.dwg",
        };

        for (String path : files) {
            File f = new File(path);
            if (!f.exists()) { System.out.println("SKIP: " + path); continue; }

            byte[] data = Files.readAllBytes(f.toPath());
            String sig = new String(data, 0, 6);
            DwgVersion ver = DwgVersionDetector.detect(data);
            System.out.println("\n=== " + path + " [" + sig + "] detected as " + ver + " ===");

            // Parse header directly
            R2007FileHeader header = R2007FileHeader.read(
                new ByteBufferBitInput(ByteBuffer.wrap(data)));
            System.out.printf("  pageMapOffset=0x%X pageMapSizeComp=%d pageMapSizeUncomp=%d%n",
                header.pageMapOffset(), header.pageMapSizeComp(), header.pageMapSizeUncomp());
            System.out.printf("  sectionsMapId=%d sectionsMapSizeComp=%d sectionsMapSizeUncomp=%d%n",
                header.sectionsMapId(), header.sectionsMapSizeComp(), header.sectionsMapSizeUncomp());

            // Read page map
            byte[] pageMapDecompressed = R2007SystemPageReader.readSystemPage(
                new ByteBufferBitInput(ByteBuffer.wrap(data)),
                0x480L + header.pageMapOffset(), header.pageMapSizeComp(),
                header.pageMapSizeUncomp(), header.pageMapCorrection());

            if (pageMapDecompressed == null || pageMapDecompressed.length == 0) {
                System.out.println("  Page map is empty/null!");
                continue;
            }

            List<R2007PageMapParser.PageMapEntry> pageMap =
                R2007PageMapParser.parsePageMap(pageMapDecompressed);
            System.out.println("  Page map entries: " + pageMap.size());
            for (int i = 0; i < Math.min(pageMap.size(), 5); i++) {
                R2007PageMapParser.PageMapEntry e = pageMap.get(i);
                System.out.printf("    [%d] pageId=%d size=%d%n", i, e.pageId, e.size);
            }

            // Find section map
            long cumulativeOffset = 0, sectionMapFileOffset = -1;
            for (R2007PageMapParser.PageMapEntry entry : pageMap) {
                if (entry.pageId == header.sectionsMapId()) {
                    sectionMapFileOffset = 0x480L + header.pageMapOffset() + cumulativeOffset;
                    break;
                }
                cumulativeOffset += entry.size;
            }
            System.out.println("  sectionMapFileOffset: 0x" + Long.toHexString(sectionMapFileOffset));

            if (sectionMapFileOffset >= 0 && sectionMapFileOffset < data.length) {
                byte[] sectionMapDecompressed = R2007SystemPageReader.readSystemPage(
                    new ByteBufferBitInput(ByteBuffer.wrap(data)),
                    sectionMapFileOffset, header.sectionsMapSizeComp(),
                    header.sectionsMapSizeUncomp(), header.sectionsMapCorrection());

                if (sectionMapDecompressed == null) {
                    System.out.println("  Section map decompressed is null!");
                    continue;
                }

                List<R2007SectionMapParser.SectionMapEntry> sectionMap =
                    R2007SectionMapParser.parseSectionMap(sectionMapDecompressed);
                System.out.println("  Section map entries: " + sectionMap.size());
                for (int i = 0; i < sectionMap.size(); i++) {
                    R2007SectionMapParser.SectionMapEntry s = sectionMap.get(i);
                    System.out.printf("    [%d] name='%s' size=%d numPages=%d%n",
                        i, s.sectionName, s.dataSize, s.numPages);
                }
            }
        }
    }
}
