package dev.atoms.agent.runtime;

import dev.atoms.agent.runtime.ResearchWorkflow.Evidence;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

final class ResearchSynthesis {
    private static final Pattern NUMBERS = Pattern.compile("\\d{2,}");
    private static final Pattern SUBSTANCE = Pattern.compile("[:：$¥€%]|https?://|www");
    private static final List<String> NO_INFO = List.of(
        "unfortunately, i cannot access",
        "unfortunately, i am unable to access",
        "unfortunately, the domain",
        "cannot connect to host",
        "failed to fetch",
        "unable to access the website",
        "unable to retrieve",
        "could not access",
        "network connection error",
        "dns resolution failed",
        "name or service not known",
        "website is offline",
        "site is unavailable",
        "suggested alternatives",
        "would you like me to try",
        "shall i attempt",
        "i couldn't find",
        "no information available",
        "unable to find",
        "no results found",
        "couldn't locate",
        "not able to find",
        "不幸的是，我无法访问",
        "不幸的是，该域名",
        "无法访问该网站",
        "无法连接到",
        "dns解析失败",
        "域名解析失败",
        "网络连接错误",
        "网站可能离线",
        "网站不可用",
        "建议的替代方案",
        "您希望我尝试",
        "是否需要我",
        "没有找到相关",
        "未找到",
        "无法找到",
        "残念ながら、アクセスできません",
        "接続できません",
        "サイトが利用できません",
        "見つかりませんでした",
        "情報が見つかりません"
    );

    record Entity(String name, List<String> aliases, List<String> domains) {}

    private ResearchSynthesis() {}

    static boolean containsAsWord(String text, String term) {
        return !term.isBlank() && Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(term)
                + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    static List<Evidence> filterCitationsByEntity(List<Evidence> evidence, Entity entity) {
        if (entity == null || entity.name().isBlank()) return evidence;
        var aliases = new LinkedHashSet<String>();
        aliases.add(normalize(entity.name()));
        entity.aliases().stream().map(ResearchSynthesis::normalize).filter(a -> !a.isEmpty()).forEach(aliases::add);
        var official = new ArrayList<Evidence>();
        var relevant = new ArrayList<Evidence>();
        var remaining = new ArrayList<Evidence>();
        for (var source : evidence) {
            String url = source.url() == null ? "" : source.url();
            String host = url.isBlank() ? "" : URI.create(url).getHost();
            host = host == null ? "" : normalize(host);
            String hostname = host;
            if (entity.domains().stream().map(ResearchSynthesis::normalize)
                    .anyMatch(domain -> !domain.isEmpty() && (hostname.equals(domain) || hostname.endsWith("." + domain)))) {
                official.add(source);
            } else if (url.isBlank() || aliases.stream().anyMatch(alias -> matches(source, hostname, alias))) {
                relevant.add(source);
            } else {
                remaining.add(source);
            }
        }
        official.addAll(relevant);
        if (official.isEmpty()) return evidence;
        remaining.sort(Comparator.comparingDouble((Evidence source) -> source.quality() * source.credibility()).reversed());
        int backfill = Math.min(remaining.size(), Math.max(0, 10 - official.size()));
        official.addAll(remaining.subList(0, backfill));
        return official;
    }

    private static boolean matches(Evidence source, String host, String alias) {
        String text = normalize(source.origin() + " " + source.text());
        if (alias.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 5)
            return text.contains(alias) || normalize(source.url()).contains(alias);
        return containsAsWord(text, alias) || Arrays.asList(host.split("\\.")).contains(alias);
    }

    static List<Evidence> preprocessAgentResults(List<Evidence> evidence) {
        var exact = new HashSet<String>();
        var seen = new ArrayList<Set<String>>();
        var filtered = new ArrayList<Evidence>();
        for (var source : evidence) {
            String normalized = normalize(source.text());
            if (normalized.isEmpty() || !exact.add(normalized)) continue;
            var words = new HashSet<>(Arrays.asList(normalized.replaceAll("[\\p{P}\\p{S}]+", " ").strip().split("\\s+")));
            words.remove("");
            if (seen.stream().anyMatch(previous -> similar(previous, words))) continue;
            seen.add(words);
            String cleaned = filterLowQuality(source.text());
            if (!cleaned.isBlank()) filtered.add(new Evidence(source.origin(), cleaned, source.url(), source.quality(), source.credibility()));
        }
        return filtered;
    }

    private static boolean similar(Set<String> left, Set<String> right) {
        long intersection = left.stream().filter(right::contains).count();
        return intersection > 0.92 * (left.size() + right.size() - intersection);
    }

    static String filterLowQuality(String text) {
        return text.lines().filter(line -> line.length() > 200
                || SUBSTANCE.matcher(line).find() || NUMBERS.matcher(line).find()
                || NO_INFO.stream().noneMatch(normalize(line)::contains))
                .collect(java.util.stream.Collectors.joining("\n")).strip();
    }

    static boolean looksComplete(String answer, int minimumLength) {
        String text = answer.stripTrailing();
        if (text.codePointCount(0, text.length()) < minimumLength || text.isEmpty()) return false;
        return ".!?\"\')]。！？』」".indexOf(text.charAt(text.length() - 1)) >= 0
                && text.lines().filter(line -> line.stripLeading().startsWith("```")).count() % 2 == 0;
    }

    static String normalize(String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }
}
