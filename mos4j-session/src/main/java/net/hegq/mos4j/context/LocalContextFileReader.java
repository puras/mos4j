package net.hegq.mos4j.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地文件系统的上下文文件读取入口（缺省实现）。
 *
 * <p><b>为什么框架里要有这个默认实现</b>：发现规则（候选名优先级、向上递归、
 * 同目录 override、真实路径去重、字节预算）是纯逻辑，可以脱离文件系统测；
 * 但「怎么读」通常是宿主的事——服务端宿主可能读对象存储里的虚拟目录。
 * 这个类只服务「就是读本地目录」的宿主（CLI、本地服务），
 * 需要更严格的边界（例如只许读声明过的根内）时，宿主自己注入
 * {@link ContextFileReader} 即可。
 *
 * <p>与 Go 参考版的 {@code OSReader} 同款定位。
 */
public final class LocalContextFileReader implements ContextFileReader {

    @Override
    public byte[] readIfExists(String path) throws IOException {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    @Override
    public String realPath(String path) {
        try {
            return Path.of(path).toRealPath().toString();
        } catch (IOException e) {
            return path;
        }
    }

    @Override
    public boolean isDirectory(String path) {
        try {
            return Files.isDirectory(Path.of(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String parentOf(String dir) {
        if (dir == null) {
            return null;
        }
        Path path = Path.of(dir).toAbsolutePath().normalize();
        Path parent = path.getParent();
        return parent == null ? null : parent.toString();
    }
}
