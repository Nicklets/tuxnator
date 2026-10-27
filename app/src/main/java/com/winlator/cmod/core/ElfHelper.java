package com.winlator.cmod.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public abstract class ElfHelper {
    private static final byte ELF_CLASS_32 = 1;
    private static final byte ELF_CLASS_64 = 2;
    public static final int EM_386 = 0x03;
    public static final int EM_X86_64 = 0x3E;
    public static final int EM_AARCH64 = 0xB7;

    private static byte[] readHeader(File binFile) {
        try (InputStream inStream = new FileInputStream(binFile)) {
            byte[] header = new byte[52];
            if (inStream.read(header) == header.length && header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F') {
                return header;
            }
        }
        catch (IOException e) {}
        return null;
    }

    private static int getEIClass(File binFile) {
        byte[] header = readHeader(binFile);
        return header != null ? header[4] : 0;
    }

    public static boolean isElf(File binFile) {
        return binFile.isFile() && readHeader(binFile) != null;
    }

    public static int getMachine(File binFile) {
        byte[] header = readHeader(binFile);
        if (header == null) return 0;
        // e_machine is at offset 18, byte order given by e_ident[EI_DATA] (1 = little endian)
        if (header[5] == 1) return (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        return ((header[18] & 0xff) << 8) | (header[19] & 0xff);
    }

    public static boolean is32Bit(File binFile) {
        return getEIClass(binFile) == ELF_CLASS_32;
    }

    public static boolean is64Bit(File binFile) {
        return getEIClass(binFile) == ELF_CLASS_64;
    }
}
