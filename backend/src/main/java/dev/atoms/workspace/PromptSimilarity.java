package dev.atoms.workspace;

import java.text.Normalizer;
import java.util.*;

/** 比较规范化后的字符对，例如忽略中文需求末尾的标点变化。 */
public final class PromptSimilarity {
    private final Set<Long> query;

    public PromptSimilarity(String prompt) {
        query = features(prompt);
    }

    public double score(String prompt) {
        var other = features(prompt);
        if (query.isEmpty() || other.isEmpty()) return 0;
        long shared = query.stream().filter(other::contains).count();
        return (double) shared / (query.size() + other.size() - shared);
    }

    private static Set<Long> features(String prompt) {
        int[] points = Normalizer.normalize(prompt, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .codePoints().filter(Character::isLetterOrDigit).toArray();
        var result = new HashSet<Long>();
        if (points.length == 1) result.add((long) points[0]);
        for (int i = 1; i < points.length; i++)
            result.add(((long) points[i - 1] << 21) | points[i]);
        return result;
    }
}
