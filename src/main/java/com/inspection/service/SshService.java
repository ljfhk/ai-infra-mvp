package com.inspection.service;

import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class SshService {

    @Value("${inspection.ssh.timeout:30000}")
    private int timeout;

    @Value("${inspection.script.path:/root/java-pro/mvp/scripts/baseline_check.sh}")
    private String localScriptPath;

    private static final String REMOTE_SCRIPT = "/tmp/baseline_check.sh";

    /**
     * 连接目标服务器并执行基线检查脚本
     * 会自动将脚本推送到目标服务器
     */
    public String executeInspection(String ip, int port, String username,
                                    String password, String keyPath) throws Exception {
        Session session = null;
        try {
            session = createSession(ip, port, username, password, keyPath);
            session.connect(timeout);
            log.info("SSH连接成功: {}@{}:{}", username, ip, port);

            // 将基线检查脚本推送到目标服务器
            transferScriptToRemote(session);

            // 执行检查脚本
            String result = executeRemoteCommand(session, "bash " + REMOTE_SCRIPT + " --json");
            log.info("脚本执行完成, ip={}, result={}", ip, result);
            return result;

        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * 通过SFTP将基线检查脚本推送到目标服务器
     */
    private void transferScriptToRemote(Session session) throws Exception {
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(timeout);
            log.info("SFTP连接成功，开始推送脚本到 {}", REMOTE_SCRIPT);

            // 读取本地脚本内容（优先从JAR包资源读取，否则读本地文件）
            InputStream scriptStream = getScriptInputStream();
            sftp.put(scriptStream, REMOTE_SCRIPT, ChannelSftp.OVERWRITE);
            scriptStream.close();

            // 设置可执行权限
            try {
                session.openChannel("exec");
                executeSimpleCommand(session, "chmod +x " + REMOTE_SCRIPT);
            } catch (Exception e) {
                log.warn("设置执行权限失败（可能已设置）: {}", e.getMessage());
            }

            log.info("脚本推送成功: {}", REMOTE_SCRIPT);
        } finally {
            if (sftp != null && sftp.isConnected()) {
                sftp.disconnect();
            }
        }
    }

    /**
     * 获取脚本内容的输入流（从JAR包资源或本地文件）
     */
    private InputStream getScriptInputStream() throws IOException {
        // 先尝试从JAR包资源读取
        Resource resource = new ClassPathResource("scripts/baseline_check.sh");
        if (resource.exists()) {
            log.info("从JAR包资源读取脚本");
            return resource.getInputStream();
        }
        // 回退到本地文件系统
        File localFile = new File(localScriptPath);
        if (localFile.exists()) {
            log.info("从本地文件读取脚本: {}", localScriptPath);
            return new FileInputStream(localFile);
        }
        throw new FileNotFoundException("基线检查脚本未找到，请检查: " + localScriptPath);
    }

    /**
     * 在远程服务器上执行命令，返回输出
     */
    private String executeRemoteCommand(Session session, String command) throws Exception {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setErrStream(System.err);

            InputStream in = channel.getInputStream();
            channel.connect(timeout);

            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
            }

            // 等待执行完成
            waitForChannelClose(channel, 30000);

            int exitStatus = channel.getExitStatus();
            log.info("命令执行完成, exitStatus={}, output={}", exitStatus, result);

            if (exitStatus != 0) {
                throw new RuntimeException("远程命令执行失败, exitStatus=" + exitStatus + ", output=" + result);
            }
            return result.toString();
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    private void waitForChannelClose(ChannelExec channel, int maxWaitMs) throws InterruptedException {
        int waited = 0;
        while (channel.isConnected() && waited < maxWaitMs) {
            Thread.sleep(500);
            waited += 500;
        }
    }

    /**
     * 创建SSH会话（支持密码或密钥认证）
     */
    private Session createSession(String ip, int port, String username,
                                 String password, String keyPath) throws JSchException {
        JSch jsch = new JSch();
        if (keyPath != null && !keyPath.isEmpty()) {
            jsch.addIdentity(keyPath);
            log.info("使用密钥认证: {}", keyPath);
        }

        Session session = jsch.getSession(username, ip, port);
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }

        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "publickey,password");
        session.setConfig(config);
        return session;
    }

    private String executeSimpleCommand(Session session, String command) throws Exception {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            InputStream in = channel.getInputStream();
            channel.connect(5000);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    /**
     * 在指定目标执行单条命令（供 Agent Loop 执行修复动作）。
     */
    public String executeCommand(String ip, int port, String username, String password, String keyPath, String command) throws Exception {
        Session session = null;
        try {
            session = createSession(ip, port, username, password, keyPath);
            session.connect(timeout);
            return executeRemoteCommand(session, command);
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }
}
