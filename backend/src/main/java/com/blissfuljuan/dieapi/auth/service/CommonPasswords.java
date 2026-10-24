package com.blissfuljuan.dieapi.auth.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Known-weak passwords: the bundled list ({@code common-passwords.txt}) plus two cheap structural checks (one character
 * repeated, a plain ascending/descending run). Offline on purpose: a password must never be sent to an outside service to
 * be checked. Comparison ignores case and every character that is not a letter or digit.
 */
@Component
public class CommonPasswords {

    private final Set<String> known = new HashSet<>();

    public CommonPasswords() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
            new ClassPathResource("common-passwords.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    known.add(normalise(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("common-passwords.txt could not be read", e);
        }
    }

    public boolean isWeak(String password) {
        String n = normalise(password);
        return known.contains(n) || n.chars().distinct().count() == 1 || isRun(n);
    }

    private static boolean isRun(String n) {
        if (n.length() < 2) {
            return false;
        }
        int step = n.charAt(1) - n.charAt(0);
        if (step != 1 && step != -1) {
            return false;
        }
        for (int i = 1; i < n.length(); i++) {
            if (n.charAt(i) - n.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }

    private static String normalise(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
