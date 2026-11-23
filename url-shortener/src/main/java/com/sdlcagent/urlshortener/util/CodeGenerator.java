package com.sdlcagent.urlshortener.util;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Produces random 7-character base62 codes using a SecureRandom source.
 */
@Component
public class CodeGenerator {

    public static final int CODE_LENGTH = 7;

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }
}
