package com.deepseekharness.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 认出 pnpm 的失败<b>属于哪一类</b>，并翻译成小白用户看得懂的一句话 + 一个能点的动作。
 *
 * <p>定位与 {@link PluginErrorHint} 完全对称：那个读 dsh 的<b>启动</b>日志，
 * 这个读 pnpm 的<b>安装</b>输出。两者都刻意不碰 Android API，判据全是字符串匹配，
 * 因此能进 {@code tools/pure-logic-test.sh} 用真实输出样本做回归 —— 这类
 * 「靠正则读别人的输出」的代码最容易在上游改一句话之后静默失效。
 *
 * <h3>为什么必须单独做，而不是继续往 PluginController 里塞 if</h3>
 *
 * <p>{@code PluginController} 原有三个分类器（{@code isPnpmEnvFailure} /
 * {@code isPkgNotFound} / {@code isNetworkFailure}）都<b>不认</b> pnpm 11 的两个新
 * 错误码。识别不出的输出会一路穿到最末尾那段兜底文案，被说成
 * 「很可能这个插件只支持从 npm registry 安装 / pnpm 拒绝执行 git 依赖的 prepare」
 * —— 对「刚发布不到一天」这个真实原因来说，这是<b>一句完全错误的诊断</b>：
 * 用户被告知「这插件装不了」，而事实是「等一天，或者点一下就能装」。
 *
 * <p>把分类集中到一个有样本的地方，是为了下次 pnpm 再改错误码时，
 * 改一处、断言红一处，而不是在两个上帝类里满地找 {@code contains}。
 *
 * <h3>踩过的坑：同一个原因有两个错误码</h3>
 *
 * <p>{@code minimumReleaseAge} 被违反时的错误码<b>上游改过名</b>：
 * <ul>
 *   <li>{@code ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION} —— issue #36 用户的真机输出；</li>
 *   <li>{@code ERR_PNPM_NO_MATURE_MATCHING_VERSION} —— pnpm 11.7.0 实测输出。</li>
 * </ul>
 * 只认一个，将来（或在另一个 pnpm 小版本上）就又瞎了。所以两个都认，并且额外认
 * {@code minimumReleaseAge} 这个设置名本身作为兜底 —— 名字改掉的可能性比错误码小得多。
 */
final class PnpmError {

    /** 分类结果。{@code NONE} 表示这段输出里没有本类能识别的已知失败。 */
    enum Kind {
        /** 没识别出已知问题（可能是成功，也可能是本类还不认识的失败）。 */
        NONE,
        /**
         * 目标版本发布时间太近，被 pnpm 的供应链保护挡住。
         *
         * <p>pnpm 11 起 {@code minimumReleaseAge} 默认 {@code 1440}（1 天），
         * 新发布的版本 24 小时内一律不解析。这<b>不是插件的问题，也不是环境坏了</b>,
         * 而且是唯一一类「什么都不用改、明天再点一次就好」的失败。
         */
        FRESH_RELEASE,
        /**
         * 依赖的构建脚本被 pnpm 拦下（{@code ERR_PNPM_IGNORED_BUILDS}）。
         *
         * <p>pnpm 11 用 {@code allowBuilds} 取代了 {@code onlyBuiltDependencies} 等一系列
         * 老设置，默认不执行未授权包的 build/postinstall。授权后重试即可。
         */
        NEEDS_BUILD_APPROVAL,
    }

    /** 一条给用户看的诊断。 */
    static final class Diag {
        Kind kind = Kind.NONE;
        /** 涉及的包名（认不出为空串）。 */
        String pkg = "";
        /** 版本号（认不出为空串）。 */
        String version = "";
        /** 发布时间原文，ISO8601（认不出为空串）。 */
        String publishedAt = "";
        /** 发生了什么，人话。 */
        String what = "";
        /** 怎么办，人话，且必须对应一个用户点得到的动作。 */
        String fix = "";
        /** 命中的原文那一段，留给愿意深究的人。 */
        String raw = "";
    }

    private PnpmError() {
    }

    /** 两个已知错误码 + 设置名兜底。 */
    private static final String[] FRESH_MARKERS = {
            "ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION",
            "ERR_PNPM_NO_MATURE_MATCHING_VERSION",
            "minimumReleaseAge",
    };

    /** 用于让别处（例如安装流程）判断「要不要提供豁免选项」的稳定标记。 */
    static final String RELEASE_AGE_MARKER = "minimumReleaseAge";

    /**
     * 从 pnpm 输出里抠出 {@code 包名@版本 was published at 时间}。
     *
     * <p>样本（pnpm 11.7.0 实测）：
     * <pre>
     * [ERR_PNPM_NO_MATURE_MATCHING_VERSION] 1 version does not meet the minimumReleaseAge constraint:
     *   is-number@7.0.0 was published at 2018-07-04T15:08:58.238Z, within the minimumReleaseAge cutoff (…)
     * </pre>
     * 包名允许 scope（{@code @a/b@1.0.0}），所以名字部分要贪心到<b>最后一个</b> {@code @}。
     */
    private static final Pattern FRESH_DETAIL = Pattern.compile(
            "((?:@[\\w.-]+/)?[\\w.-]+)@([\\w.+-]+)\\s+was published at\\s+([0-9T:.Z+-]+)");

    /** {@code Ignored build scripts: es5-ext@0.10.64}（可能有多个，取第一个）。 */
    private static final Pattern IGNORED_BUILDS = Pattern.compile(
            "[Ii]gnored build scripts?:\\s*((?:@[\\w.-]+/)?[\\w.-]+)");

    /** 认不出返回 {@code null}（调用方据此决定要不要走原有的兜底文案）。 */
    static Diag detect(String out) {
        if (out == null || out.isEmpty()) return null;

        if (containsAny(out, FRESH_MARKERS)) {
            Diag d = new Diag();
            d.kind = Kind.FRESH_RELEASE;
            Matcher m = FRESH_DETAIL.matcher(out);
            if (m.find()) {
                d.pkg = m.group(1);
                d.version = m.group(2);
                d.publishedAt = m.group(3);
                d.raw = m.group();
            } else {
                d.raw = firstLineContaining(out, FRESH_MARKERS);
            }
            String who = d.pkg.isEmpty() ? "这个插件" : "「" + d.pkg + "」";
            String ver = d.version.isEmpty() ? "" : "（" + d.version + " 版）";
            d.what = who + ver + "是刚刚才发布的"
                    + (d.publishedAt.isEmpty() ? "" : "，发布时间 " + d.publishedAt)
                    + "。\n\n包管理器默认要等新版本满 24 小时才肯安装 —— 这是在防"
                    + "「有人偷偷发了个带毒的版本、被立刻装走」。\n"
                    + "所以这不是插件坏了，也不是你的手机有问题。";
            d.fix = "最省事的办法是明天再来点一次，那时它自然就装得上了。\n"
                    + "如果你认识这个插件的作者、确定它没问题，也可以选择现在就装 —— "
                    + "只对这一个插件破例，其它插件照旧受保护。";
            return d;
        }

        if (out.contains("ERR_PNPM_IGNORED_BUILDS")
                || out.contains("Ignored build scripts")) {
            Diag d = new Diag();
            d.kind = Kind.NEEDS_BUILD_APPROVAL;
            Matcher m = IGNORED_BUILDS.matcher(out);
            if (m.find()) {
                d.pkg = m.group(1);
                d.raw = m.group();
            }
            String who = d.pkg.isEmpty() ? "这个插件" : "「" + d.pkg + "」";
            d.what = who + "安装时需要在你的手机上现场编译一小段代码，"
                    + "而包管理器默认不允许陌生的包这么做（同样是防投毒）。";
            d.fix = "已经自动为它开了许可并重试，通常一次就好。"
                    + "如果还是不行，把这段输出复制下来发到 DSHA 的 GitHub issue。";
            return d;
        }

        return null;
    }

    /** 这段输出是不是「版本太新被挡住」——安装流程用它决定要不要给用户豁免选项。 */
    static boolean isFreshRelease(String out) {
        Diag d = detect(out);
        return d != null && d.kind == Kind.FRESH_RELEASE;
    }

    /** 这段输出是不是「构建脚本没授权」。 */
    static boolean needsBuildApproval(String out) {
        Diag d = detect(out);
        return d != null && d.kind == Kind.NEEDS_BUILD_APPROVAL;
    }

    /** 拼成可以直接贴到界面上的一段（认不出返回空串）。 */
    static String describe(String out) {
        Diag d = detect(out);
        if (d == null) return "";
        return d.what + "\n\n→ " + d.fix;
    }

    private static boolean containsAny(String s, String[] needles) {
        for (String n : needles) {
            if (s.contains(n)) return true;
        }
        return false;
    }

    /** 取命中标记的那一行，给「认出类别但抠不到细节」时当 raw 用。 */
    private static String firstLineContaining(String out, String[] needles) {
        for (String line : out.split("\n", -1)) {
            if (containsAny(line, needles)) return line.trim();
        }
        return "";
    }
}
