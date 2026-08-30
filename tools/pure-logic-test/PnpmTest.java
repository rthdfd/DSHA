package com.deepseekharness.app;

/**
 * pnpm 相关纯逻辑的断言集：{@link PnpmEnv}（配置注入）+ {@link PnpmError}（失败分类）。
 *
 * <p><b>为什么另起一个文件而不是加进 PureLogicTest</b>：那个文件已经 1000 行、覆盖十几个
 * 互不相干的类，再往里堆只会让「哪条断言属于哪个功能」更难找。pnpm 这一块的判据全都绑在
 * 一个外部工具的行为上（错误码措辞、配置读取位置），上游一变就要整段回看 —— 单独一个
 * 文件，回看的范围就是这个文件。
 *
 * <p>代价是三个断言辅助方法（{@code eq} / {@code ok} / {@code report}）与 PureLogicTest
 * 重复。这点重复是刻意接受的：它们是十几行测试脚手架，不是业务判断；而把它们抽成公共类
 * 需要改动那 1000 行文件的每一处调用，得不偿失。
 *
 * <p>由 {@code tools/pure-logic-test.sh} 与 PureLogicTest 一起跑，任一失败整条 CI 红。
 */
public final class PnpmTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        pnpmEnv();
        pnpmError();

        System.out.println();
        System.out.println(fail == 0
                ? "pnpm 断言全部通过：" + pass + " 条"
                : "pnpm 断言失败 " + fail + " 条（通过 " + pass + "）");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * pnpm 11 的配置注入。
     *
     * <p>为什么这几条必须有断言：pnpm 11 起 {@code .npmrc} 只读 auth 与 registry，其它设置
     * 一概不认，必须走 {@code pnpm-workspace.yaml} 或 {@code pnpm_config_*} 环境变量。
     * 而环境变量名<b>必须是 snake_case</b> —— 实测 {@code pnpm_config_packageImportMethod}
     * （驼峰）返回 {@code undefined} 且不报错，正是最难发现的那种失效：名字拼错一个字符，
     * proot 下的硬链接修复就整条哑掉，症状是「装任何插件都报莫名的 ENOENT」，
     * 而且没有任何报错指向配置。
     */
    private static void pnpmEnv() {
        System.out.println("── PnpmEnv：pnpm 11 配置注入 ──");
        eq("pnpmEnv: 驼峰转下划线", "package_import_method", PnpmEnv.snake("packageImportMethod"));
        eq("pnpmEnv: 单词不变", "registry", PnpmEnv.snake("registry"));
        eq("pnpmEnv: 三段名", "minimum_release_age", PnpmEnv.snake("minimumReleaseAge"));
        eq("pnpmEnv: 环境变量前缀是 pnpm_config_（pnpm 11 不再读 npm_config_）",
                "pnpm_config_package_import_method", PnpmEnv.envName("packageImportMethod"));

        String ex = PnpmEnv.exportScript(false);
        ok("pnpmEnv: 导出里有 copy 导入法（proot 下硬链接是模拟的，会留悬空链）",
                ex.contains("pnpm_config_package_import_method='copy'"), ex);
        ok("pnpmEnv: 导出里关掉 side-effects 缓存（它同样靠硬链接铺开）",
                ex.contains("pnpm_config_side_effects_cache='false'"), ex);
        // 默认必须是安全的：minimumReleaseAge 是防投毒的保护，程序不替用户关
        ok("pnpmEnv: 默认**不**降低发布年龄门槛（那是用户明确点了才该有的）",
                !ex.contains("minimum_release_age"), ex);
        ok("pnpmEnv: 明确要求豁免时才出现 minimumReleaseAge=0",
                PnpmEnv.exportScript(true).contains("pnpm_config_minimum_release_age='0'"));
        ok("pnpmEnv: 每条 export 都以分号空格结尾（可直接接下一条命令）",
                ex.endsWith("; "), ex);
        // registry 目前来自常量，但仍然过 ShellQuote —— 将来有人把它做成可配置项时，
        // 用户输入会直接进 bash -c
        ok("pnpmEnv: registry 经 shell 转义",
                PnpmEnv.exportScript("https://r.example.com/a b", false)
                        .contains("'https://r.example.com/a b'"));
        ok("pnpmEnv: registry 为空时不设这一项",
                !PnpmEnv.exportScript("", false).contains("pnpm_config_registry"));

        // .npmrc 只留 registry。继续往里写 pnpm 设置会制造「配了但没生效」的幻觉，
        // 让下一个看代码的人以为问题已经修好了 —— 这正是这次 bug 藏了几个月的成因。
        eq("npmrc: 只有 registry 一行", "registry=https://registry.npmmirror.com\n",
                PnpmEnv.npmrcContent(null));
        ok("npmrc: 不再写已失效的 package-import-method",
                !PnpmEnv.npmrcContent(null).contains("package-import-method"));
        // 末尾必须是真换行。曾经在 shell 里拼 printf 'registry=…\n'，经 Java 与 shell 两层
        // 转义后 printf 收到字面反斜杠，registry 值末尾带上 \n，npm 规范化成
        // …npmmirror.com/n —— 于是每次真查 registry 的安装都 404。
        ok("npmrc: 内容里没有反斜杠（曾因 printf 转义把 registry 写成 …/n 导致全线 404）",
                !PnpmEnv.npmrcContent(null).contains("\\"));
        ok("npmrc: 写入片段用 echo + 转义，不用 printf",
                PnpmEnv.writeNpmrcScript(null).startsWith("echo '")
                        && !PnpmEnv.writeNpmrcScript(null).contains("printf"));
    }

    /**
     * 安装失败的分类与人话。样本全部取自真实输出。
     *
     * <p>关键：同一个原因有<b>两个</b>错误码 —— issue #36 用户的真机输出是
     * {@code ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION}，而 pnpm 11.7.0 实测输出的是
     * {@code ERR_PNPM_NO_MATURE_MATCHING_VERSION}。只认一个，换个小版本就又瞎了。
     */
    private static void pnpmError() {
        System.out.println("── PnpmError：安装失败分类与人话 ──");
        String freshNew = "Progress: resolved 1, reused 0, downloaded 0, added 0\n"
                + "[ERR_PNPM_NO_MATURE_MATCHING_VERSION] 1 version does not meet the"
                + " minimumReleaseAge constraint:\n"
                + "  is-number@7.0.0 was published at 2018-07-04T15:08:58.238Z, within the"
                + " minimumReleaseAge cutoff (1836-07-13T00:11:04.015Z)\n";
        PnpmError.Diag d1 = PnpmError.detect(freshNew);
        ok("pnpmError: 认出 NO_MATURE_MATCHING_VERSION（pnpm 11.7.0 实测样本）",
                d1 != null && d1.kind == PnpmError.Kind.FRESH_RELEASE);
        eq("pnpmError: 抠出包名", "is-number", d1 == null ? null : d1.pkg);
        eq("pnpmError: 抠出版本", "7.0.0", d1 == null ? null : d1.version);
        eq("pnpmError: 抠出发布时间", "2018-07-04T15:08:58.238Z",
                d1 == null ? null : d1.publishedAt);

        PnpmError.Diag d2 = PnpmError.detect(
                "ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION  Cannot install @scope/pkg-a@1.2.3-rc.1\n");
        ok("pnpmError: 也认旧错误码 MINIMUM_RELEASE_AGE_VIOLATION（issue #36 的真机输出）",
                d2 != null && d2.kind == PnpmError.Kind.FRESH_RELEASE);

        // scope 包名要贪心到最后一个 @，否则 @scope/pkg@1.0.0 会被切成 @scope
        PnpmError.Diag d3 = PnpmError.detect(
                "@my-org/dsh-thing@0.4.1 was published at 2026-08-30T02:38:22.455Z, within the"
                        + " minimumReleaseAge cutoff (…)");
        eq("pnpmError: scope 包名不被 @ 切坏", "@my-org/dsh-thing", d3 == null ? null : d3.pkg);
        eq("pnpmError: scope 包的版本", "0.4.1", d3 == null ? null : d3.version);
        ok("pnpmError: isFreshRelease 便捷判定", PnpmError.isFreshRelease(freshNew));

        // 措辞对小白用户的要求：说清「不是你的错」，并给出「等一天」这个零成本动作。
        // 这两句是这个类存在的全部理由 —— 原来的兜底文案把它诊断成「这插件只能从 npm
        // 装、装不了」，是一句完全错误的结论。
        String say = PnpmError.describe(freshNew);
        ok("pnpmError: 文案说明这不是插件坏了/手机的问题",
                say.contains("不是插件坏了") && say.contains("手机"), say);
        ok("pnpmError: 文案给出「明天再来」这个零成本选择", say.contains("明天"), say);
        ok("pnpmError: 文案不出现 pnpm/registry 这类术语（这一屏是给小白看的）",
                !say.contains("pnpm") && !say.contains("registry"), say);

        String ignored = "[ERR_PNPM_IGNORED_BUILDS] Ignored build scripts: es5-ext@0.10.64\n"
                + "Run \"pnpm approve-builds\" to pick which dependencies should be allowed"
                + " to run scripts.\n";
        PnpmError.Diag d4 = PnpmError.detect(ignored);
        ok("pnpmError: 认出构建脚本被拦（pnpm 11 实测样本）",
                d4 != null && d4.kind == PnpmError.Kind.NEEDS_BUILD_APPROVAL);
        eq("pnpmError: 抠出要授权的包", "es5-ext", d4 == null ? null : d4.pkg);
        ok("pnpmError: needsBuildApproval 便捷判定", PnpmError.needsBuildApproval(ignored));

        // 成功输出与不相关的失败都必须返回 null，否则会把正确的流程带偏
        ok("pnpmError: 成功输出不误判", PnpmError.detect(
                "dependencies:\n+ is-number 7.0.0\n\nDone in 3.4s\nINSTALL_EXIT=0") == null);
        ok("pnpmError: 网络失败不误判成别的类",
                PnpmError.detect("ENOTFOUND registry.npmmirror.com") == null);
        ok("pnpmError: null / 空串安全",
                PnpmError.detect(null) == null && PnpmError.detect("") == null);
        eq("pnpmError: 认不出时 describe 给空串", "", PnpmError.describe("whatever"));
    }

    // ── 断言脚手架（与 PureLogicTest 同形，见类注释里为什么接受这点重复）──

    private static void eq(String name, String expected, String actual) {
        boolean good = expected == null ? actual == null : expected.equals(actual);
        report(name, good, String.valueOf(expected), String.valueOf(actual));
    }

    private static void ok(String name, boolean good) {
        report(name, good, "true", String.valueOf(good));
    }

    private static void ok(String name, boolean good, String extra) {
        report(name, good, "true", extra);
    }

    private static void report(String name, boolean good, String expected, String actual) {
        if (good) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name
                    + "\n         期望: " + expected + "\n         实际: " + actual);
        }
    }
}
