package com.deepseekharness.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * pnpm 11 的配置注入 —— <b>唯一定义</b>。纯逻辑，只生成字符串，不碰文件也不碰 Android API。
 *
 * <h3>为什么必须有这个类</h3>
 *
 * <p>pnpm 11 有一条破坏性变更，把这个项目里三处「已经修好的」故障悄悄改回了未修状态：
 * <b>{@code .npmrc} 只再被用于 auth 与 registry，其余设置一概不读</b>，必须写进
 * {@code pnpm-workspace.yaml} 或全局 {@code ~/.config/pnpm/config.yaml}
 * （<a href="https://pnpm.io/blog/releases/11.0">pnpm 11.0 release notes</a>）。
 *
 * <p>而仓库里有三处都在往 {@code .npmrc} 写 pnpm 设置：
 * {@code assets/pnpm-env-fix.sh}、{@code HarnessController} 装依赖那一步、
 * {@code PluginController.runPluginInstall}。它们写的
 * {@code package-import-method=copy} 从 pnpm 11 起<b>完全无效</b>——实测
 * {@code pnpm config get packageImportMethod} 返回 {@code undefined}，
 * 搬进 yaml 才返回 {@code copy}。
 *
 * <p>失效的后果是链式的，而且没有任何报错指向真正的原因：
 * <pre>
 *   packageImportMethod=copy 失效
 *     → pnpm 回到硬链接导入
 *     → Android 私有目录不支持真硬链接，proot 只能 --link2symlink 模拟成 .l2s
 *     → 临时文件一清理，目标就悬空
 *     → 装插件报各种莫名的 ENOENT
 * </pre>
 * 更糟的是重试路径也一起死了：{@code installPluginLocked} 检测到这类失败会跑
 * {@code pnpm-env-fix.sh} 再试一次，而那个脚本现在是个空操作，第二次以完全相同的
 * 方式失败。用户看到的就是「怎么都装不上」。
 *
 * <h3>为什么用环境变量，而不是去改 pnpm-workspace.yaml</h3>
 *
 * <p>最省事的做法是往 {@code /root/.dsh/profiles/web/pnpm-workspace.yaml} 里写几行。
 * 但那个文件是<b>用户的、也是 dsh 自己在管的</b>：{@link PatchToggle#withAllowBuild}
 * 已经在写它，dsh 自己也会动它。再加一个写入方，就是这个项目反复栽过的那个模式
 * ——「同一份文件多个写入方」。issue #36 里 repair 机制把 {@code cordis.patch.yml}
 * 拼成非法 YAML、导致 Web 完全起不来，正是这个模式的产物。
 *
 * <p>{@code pnpm_config_*} 环境变量的优先级<b>高于</b> {@code pnpm-workspace.yaml}
 * （<a href="https://pnpm.io/configuring">Configuring</a>：命令行 &gt; 环境变量 &gt; yaml），
 * 而且无状态、幂等、不留痕 —— 一次安装用一次，不会影响别的安装，更不会在用户文件里
 * 留下需要将来清理的东西。
 *
 * <h3>踩过的坑：必须是 snake_case</h3>
 *
 * <p>变量名是 {@code pnpm_config_} + <b>下划线小写</b>的设置名。实测：
 * <pre>
 *   pnpm_config_package_import_method=copy  → pnpm config get packageImportMethod = copy
 *   pnpm_config_packageImportMethod=copy    → undefined   ← 静默失效
 * </pre>
 * 驼峰写法不会报错，只是当没设过 —— 正是最难发现的那种错。所以名字一律由
 * {@link #snake(String)} 生成，并在 {@code tools/pure-logic-test.sh} 里有断言兜着。
 *
 * <p>注意 pnpm 11 也不再读 {@code npm_config_*} 前缀，只读 {@code pnpm_config_*}。
 */
final class PnpmEnv {

    /** 默认 registry（国内直连 npmjs.org 基本不通）。 */
    static final String REGISTRY = "https://registry.npmmirror.com";

    /** 官方源，镜像出问题时的备选。 */
    static final String REGISTRY_OFFICIAL = "https://registry.npmjs.org";

    private PnpmEnv() {
    }

    /**
     * 容器里跑 pnpm 必须带的设置（不含 registry —— 那个由调用方决定用镜像还是官方源）。
     *
     * <p>每一项都对应一个实际故障，不是「顺手配一下」：
     * <ul>
     *   <li>{@code packageImportMethod=copy}：proot 下硬链接是 {@code --link2symlink}
     *       模拟的，留下的 {@code .l2s} 悬空链会变成莫名的 ENOENT。Android 私有目录
     *       本来就不支持真硬链接，所以 copy 也没损失什么。</li>
     *   <li>{@code sideEffectsCache=false}：它缓存的是 build 后的产物，同样靠硬链接铺开。</li>
     * </ul>
     */
    static Map<String, String> baseSettings() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("packageImportMethod", "copy");
        m.put("sideEffectsCache", "false");
        return m;
    }

    /**
     * 生成可以直接拼进 {@code bash -c} 的一段 {@code export}。
     *
     * @param registry        registry 地址；null / 空则不设（沿用容器里已有的配置）
     * @param allowFreshRelease true 时把 {@code minimumReleaseAge} 降为 0，
     *                          也就是<b>允许安装刚发布不久的版本</b>。
     *                          这是用户在弹窗里明确点了「我信得过，现在就装」才该传 true
     *                          的东西 —— 详见 {@link PnpmError#RELEASE_AGE_MARKER} 那段说明。
     * @return 形如 {@code export pnpm_config_x='v'; export pnpm_config_y='w'; }，
     *         末尾带分号和空格，可以直接接下一条命令；没有任何设置时返回空串
     */
    static String exportScript(String registry, boolean allowFreshRelease) {
        LinkedHashMap<String, String> all = new LinkedHashMap<>(baseSettings());
        if (registry != null && !registry.trim().isEmpty()) {
            all.put("registry", registry.trim());
        }
        if (allowFreshRelease) {
            // 只降这一次调用的门槛。不写进任何配置文件 —— 下一次安装自动恢复默认防护。
            all.put("minimumReleaseAge", "0");
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            sb.append("export ").append(envName(e.getKey()))
                    .append('=').append(ShellQuote.arg(e.getValue())).append("; ");
        }
        return sb.toString();
    }

    /** 默认 registry 的便捷形式。 */
    static String exportScript(boolean allowFreshRelease) {
        return exportScript(REGISTRY, allowFreshRelease);
    }

    /** 设置名 → 环境变量名。{@code packageImportMethod} → {@code pnpm_config_package_import_method}。 */
    static String envName(String setting) {
        return "pnpm_config_" + snake(setting);
    }

    /**
     * 驼峰 → 下划线小写。
     *
     * <p>只处理「小写/数字 后面跟大写」这一种边界，够用且不会把
     * {@code minimumReleaseAge} 这类正常名字切坏。连续大写（缩写）不在 pnpm 的
     * 设置名里出现，所以刻意不做那种复杂处理 —— 真出现了断言会红。
     */
    static String snake(String camel) {
        if (camel == null || camel.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(camel.length() + 6);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * {@code /root/.npmrc} 应该长什么样。
     *
     * <p><b>只留 registry</b>：pnpm 11 起这个文件里其它设置一概不读，
     * 继续往里写 {@code package-import-method=copy} 只会制造「配了但没生效」的幻觉，
     * 让下一个来看这段代码的人以为问题已经解决了。
     *
     * <p>registry 留着是有用的：pnpm 仍然从 {@code .npmrc} 读 registry 与 auth，
     * 而容器里还有 {@code npm}（{@code installFromGitClone} 用它装 devDeps）也要读它。
     *
     * <p>末尾的换行必须是<b>真换行</b>。这里刻意返回带 {@code \n} 的字符串并要求调用方
     * 用 {@code printf '%s'} 或 heredoc 写出去，而不是在 shell 里拼 {@code printf 'x\\n'}
     * —— 后者经 Java 与 shell 两层转义后 printf 收到的是字面反斜杠，写进文件的 registry
     * 值末尾就带上了 {@code \n}，npm 规范化后变成 {@code …npmmirror.com/n}，
     * 于是每次真要查 registry 的安装都 404。这个 bug 真的发生过。
     */
    static String npmrcContent(String registry) {
        String r = registry == null || registry.trim().isEmpty() ? REGISTRY : registry.trim();
        return "registry=" + r + "\n";
    }

    /** 写 {@code /root/.npmrc} 的 shell 片段（用 echo 一行，避开 printf 的转义陷阱）。 */
    static String writeNpmrcScript(String registry) {
        String r = registry == null || registry.trim().isEmpty() ? REGISTRY : registry.trim();
        return "echo " + ShellQuote.arg("registry=" + r) + " > /root/.npmrc; ";
    }
}
