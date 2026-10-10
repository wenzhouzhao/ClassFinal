package net.roseboy.classfinal;


import net.roseboy.classfinal.util.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * java class解密
 *
 * @author roseboy
 */
public class JarDecryptor {
    //单例
    private static final JarDecryptor single = new JarDecryptor();
    //机器码
    private char[] code;
    //加密后文件存放位置
    private static final String ENCRYPT_PATH = "META-INF/" + Const.FILE_NAME + "/";

    /**
     * 单例
     *
     * @return 单例
     */
    public static JarDecryptor getInstance() {
        return single;
    }

    /**
     * 构造
     */
    public JarDecryptor() {
        this.code = SysUtils.makeMarchinCode();
    }

    /**
     * 根据名称解密出一个文件
     *
     * @param projectPath 项目所在的路径
     * @param fileName    文件名
     * @param password    密码
     * @return 解密后的字节
     */
    public byte[] doDecrypt(String projectPath, String fileName, char[] password) {
        long t1 = System.currentTimeMillis();
        File workDir = new File(projectPath);
        byte[] bytes = readEncryptedFile(workDir, fileName);
        if (bytes == null) {
            return null;
        }

        //读取机器码，有机器码，先用机器码解密
        byte[] codeBytes = readEncryptedFile(workDir, Const.CONFIG_CODE);
        if (codeBytes != null) {
            //本机器码和打包的机器码不匹配
            if (!StrUtils.equal(EncryptUtils.md5(this.code), StrUtils.toChars(codeBytes))) {
                Log.println("该项目不可在此机器上运行!\n");
                System.exit(-1);
            }

            //用机器码解密
            char[] pass = StrUtils.merger(fileName.toCharArray(), code);
            bytes = EncryptUtils.de(bytes, pass, Const.ENCRYPT_TYPE);
        }

        //无密码启动,读取隐藏的密码
        if (password.length == 1 && password[0] == '#') {
            password = readPassFromJar(workDir);
        }

        //密码解密
        char[] pass = StrUtils.merger(password, fileName.toCharArray());
        bytes = EncryptUtils.de(bytes, pass, Const.ENCRYPT_TYPE);

        long t2 = System.currentTimeMillis();
        Log.debug("解密: " + fileName + " (" + (t2 - t1) + " ms)");

        return bytes;

    }

    /**
     * 加密条目索引缓存：projectPath -> META-INF/.classes 下的条目名集合。
     *
     * <p>为什么必须有这个缓存：{@link AgentTransformer} 会对**每一个**被加载的类回调一次，
     * 而绝大多数类（Spring、Tomcat、JDK 等）都不在加密集合里。如果每次回调都去
     * {@code new ZipFile} 查条目，仅打开 400MB fat jar 的中央目录就要十几毫秒，
     * 几千个类累计下来，启动时间会从约 20 秒涨到 2 分多钟（实测 22s → 138s）。
     * 这里把条目名预先列一遍，之后只做 HashSet 判存在，零 I/O。
     */
    /**
     * 加密条目索引缓存：projectPath -> META-INF/.classes 下的条目名集合。
     *
     * <p>用来挡掉绝大多数「不在加密集合里」的类（Spring、Tomcat、JDK 等），
     * 使它们不做任何 zip 查询。
     */
    private static final Map<String, Set<String>> ENCRYPTED_INDEX = new ConcurrentHashMap<String, Set<String>>();

    /**
     * 已打开的 jar 句柄缓存。
     *
     * <p>关键性能点：{@link AgentTransformer} 对每个被加载的类回调一次，其中约 1500 个
     * 是本项目的加密类。若每次都 {@code new ZipFile} 再 {@code getEntry}，光打开 400MB
     * fat jar 的中央目录就要几十毫秒，1500 次累计 **两分多钟**（实测启动 17s → 138s）。
     * {@link ZipFile} 的读取是线程安全的，保持打开即可 —— 这与 JVM 把 classpath 上的
     * jar 一直开着是同一回事。
     */
    private static final Map<String, ZipFile> ZIP_CACHE = new ConcurrentHashMap<String, ZipFile>();

    /**
     * 取（必要时打开）一个常驻的 jar 句柄
     *
     * @param jar jar 文件
     * @return 可复用的 ZipFile
     * @throws IOException 打开失败
     */
    private static ZipFile openJar(File jar) throws IOException {
        String key = jar.getAbsolutePath();
        ZipFile cached = ZIP_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        ZipFile created = new ZipFile(jar);
        ZipFile prev = ZIP_CACHE.putIfAbsent(key, created);
        if (prev != null) {
            created.close();
            return prev;
        }
        return created;
    }

    /**
     * 取（必要时构建）某个 jar / 目录下 META-INF/.classes 的条目名集合
     *
     * @param workDir jar文件或解压后的目录
     * @return 条目名集合
     */
    private static Set<String> encryptedIndex(File workDir) {
        String key = workDir.getAbsolutePath();
        Set<String> cached = ENCRYPTED_INDEX.get(key);
        if (cached != null) {
            return cached;
        }

        Set<String> names = new HashSet<String>();
        try {
            if (workDir.isFile()) {
                Enumeration<? extends ZipEntry> entries = openJar(workDir).entries();
                while (entries.hasMoreElements()) {
                    String entryName = entries.nextElement().getName();
                    if (entryName.startsWith(ENCRYPT_PATH)) {
                        names.add(entryName.substring(ENCRYPT_PATH.length()));
                    }
                }
            } else {//war解压的目录
                String[] list = new File(workDir, ENCRYPT_PATH).list();
                if (list != null) {
                    names.addAll(Arrays.asList(list));
                }
            }
            Log.debug("建立加密条目索引: " + key + " (" + names.size() + " 项)");
        } catch (Exception e) {
            // 索引建不起来就当作「没有加密条目」，让调用方按原有逻辑处理，不影响启动
            Log.debug("建立加密条目索引失败: " + key + " -> " + e.getMessage());
        }

        ENCRYPTED_INDEX.put(key, names);
        return names;
    }

    /**
     * 在jar文件或目录中读取文件字节
     *
     * @param workDir jar文件或目录
     * @param name    文件名
     * @return 文件字节数组
     */
    public static byte[] readEncryptedFile(File workDir, String name) {
        if (workDir == null) {
            return null;
        }
        // 先用索引挡掉绝大多数「不在加密集合里」的类，避免无谓的 zip 查询
        if (!encryptedIndex(workDir).contains(name)) {
            return null;
        }

        String fileName = ENCRYPT_PATH + name;
        //jar文件
        if (workDir.isFile()) {
            try {
                ZipEntry entry = openJar(workDir).getEntry(fileName);
                if (entry == null) {
                    return null;
                }
                try (InputStream is = openJar(workDir).getInputStream(entry)) {
                    return IoUtils.toBytes(is);
                }
            } catch (Exception e) {
                Log.debug("读取加密条目失败: " + fileName + " -> " + e.getMessage());
                return null;
            }
        }
        //war解压的目录
        File file = new File(workDir, fileName);
        if (file.exists()) {
            return IoUtils.readFileToByte(file);
        }
        return null;
    }

    /**
     * 读取隐藏在jar的密码
     *
     * @param workDir jar路径
     * @return 密码char
     */
    public static char[] readPassFromJar(File workDir) {
        byte[] passbyte = readEncryptedFile(workDir, Const.CONFIG_PASS);
        if (passbyte != null) {
            char[] pass = StrUtils.toChars(passbyte);
            return EncryptUtils.md5(pass);
        }
        return null;
    }

    /**
     * 解密配置文件，spring读取文件时调用
     *
     * @param path 配置文件路径
     * @param in   输入流
     * @return 解密的输入流
     */
    public InputStream decryptConfigFile(String path, InputStream in, char[] pass) {
        if (path.endsWith(".class")) {
            return in;
        }
        String projectPath = JarUtils.getRootPath(null);
        if (StrUtils.isEmpty(projectPath)) {
            return in;
        }
        byte[] bytes = null;
        try {
            bytes = IoUtils.toBytes(in);
        } catch (Exception e) {

        }
        if (bytes == null || bytes.length == 0) {//需要解密
            bytes = this.doDecrypt(projectPath, path, pass);
        }
        if (bytes == null) {
            return in;
        }
        in = new ByteArrayInputStream(bytes);
        return in;
    }
}
