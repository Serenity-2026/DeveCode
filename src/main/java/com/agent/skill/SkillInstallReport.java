package com.agent.skill;

/**
 * 安装完成后的汇报信息，由 {@link SkillInstaller#install} 返回，
 * 工具层用它生成给模型的成功消息。
 *
 * @param skillName    安装的 skill 名称
 * @param targetDir    最终安装目录的绝对路径
 * @param fileCount    下载的文件总数
 * @param skippedFiles 跳过的文件数（超过单文件大小上限的宣传素材等）
 * @param totalBytes   下载的字节总数
 */
public record SkillInstallReport(
        String skillName,
        String targetDir,
        int fileCount,
        int skippedFiles,
        long totalBytes
) {}
