package dev.dota.etl.util;

/** Small numeric string helpers shared across CLI and report code. */
public final class Numbers {

    public static boolean isDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private Numbers() {
    }
}
