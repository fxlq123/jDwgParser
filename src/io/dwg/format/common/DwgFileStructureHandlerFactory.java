package io.dwg.format.common;

import io.dwg.core.version.DwgVersion;
import io.dwg.core.exception.DwgVersionException;

/**
 * DwgVersion에 맞는 DwgFileStructureHandler 인스턴스를 반환하는 팩토리
 */
public class DwgFileStructureHandlerFactory {

    /**
     * version switch → 적합한 Handler 인스턴스 반환
     */
    public static DwgFileStructureHandler forVersion(DwgVersion version) {
        switch (version) {
            case R13:
            case R14:
                return new io.dwg.format.r13.R13FileStructureHandler();
            case R2000:
                return new io.dwg.format.r2000.R2000FileStructureHandler();
            case R2004:
                return new io.dwg.format.r2004.R2004FileStructureHandler();
            case R2007:
            case R2010:
            case R2013:
                // R2007-R2013 use Page Map → Section Map based format (libredwg's decode_R2007).
                return new io.dwg.format.r2007.R2007FileStructureHandler();
            case R2018:
                // R2018 (AC1032) uses a different plaintext header format without RS-encoding
                return new io.dwg.format.r2018.R2018FileStructureHandler();
            default:
                throw new DwgVersionException("Unsupported version: " + version);
        }
    }

    /**
     * 파일 헤더에서 버전 자동 감지 후 위임
     */
    public static DwgFileStructureHandler detect(byte[] fileBytes) {
        DwgVersion version = io.dwg.core.version.DwgVersionDetector.detect(fileBytes);
        return forVersion(version);
    }
}
