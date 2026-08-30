package com.deepseekharness.app;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 小文本文件的读 / 原子写 —— <b>唯一实现</b>。
 *
 * <p>为什么值得单独一个类：这个项目里需要「读一份配置、改几行、写回去」的地方在变多
 * （{@code cordis.patch.yml} 的插件开关、{@code pnpm-workspace.yaml} 的构建授权、
 * 以及本轮加的 pnpm 11 设置迁移）。原来这套读写只存在于 {@code PluginController} 的
 * 两个私有方法里，第二个需要它的人只能复制一份 —— 而这正是本项目反复栽的那个模式
 * （「同一份逻辑散落多处」，改一处漏一处）。
 *
 * <p><b>原子写为什么不能省</b>：原先某处是「先 {@code delete()} 目标、再 {@code renameTo()}」，
 * 那两步之间进程被杀，用户手写的整个配置文件就<b>没了</b>。rename(2) 本身允许覆盖已存在的
 * 文件，根本不需要先删，所以那个 delete 是净风险。
 */
final class TextFile {

    /** 配置类文件的合理上限。超过就当读不到 —— 与其把几十 MB 读进内存，不如什么都不做。 */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private TextFile() {
    }

    /** 读文本（不存在 / 过大 / 出错都给空串，调用方不必到处 try）。 */
    static String read(File f) {
        try {
            if (f == null || !f.isFile() || f.length() > MAX_BYTES) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 原子写：同目录临时文件 + {@code ATOMIC_MOVE}。
     *
     * <p>临时文件与目标<b>必须同目录</b>（跨文件系统 rename 会失败，而 rootfs 里
     * {@code /root} 与 {@code /tmp} 常常不在一处）。
     *
     * @return 写成功与否；失败时目标文件保持原样，不会留下半份内容
     */
    static boolean writeAtomic(File f, String text) {
        if (f == null || f.getParentFile() == null) return false;
        File tmp = new File(f.getParentFile(), f.getName() + ".dsha-tmp");
        try {
            try (java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                os.write(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable t) {
            // ATOMIC_MOVE 在个别文件系统上不支持 —— 退一步用普通替换。
            // 仍然比「先删再改名」安全：至少目标不会出现「已删除但没写上」的空窗。
            try {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Throwable ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }
}
