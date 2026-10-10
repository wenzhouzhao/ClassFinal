# classfinal-demo

一组可运行的 Spring Boot 示例，用来验证 ClassFinal 在不同**打包形态**下能否正常加密与解密。
每个示例都会自动走一遍：**构建 -> 基线运行 -> 加密 -> 加密运行 -> 断言输出**。

## 前提

- JDK 17+（示例用 Java 17 编译）
- Maven
- 首次构建需联网拉取 Spring Boot 依赖

## 一键验证

```bash
# 在仓库根先构建工具，再跑全部 demo
classfinal-demo/run-all.sh
```

脚本会先构建 `classfinal-core` + `classfinal-fatjar`，然后依次运行下面四个 demo，
任一断言失败即以非零码退出。

## 单独运行某个 demo

```bash
# 需要 classfinal-fatjar 已构建（mvn package -DskipTests）
cd classfinal-demo/thin-multi
./run.sh
```

## 目录说明

| 目录 | 形态 | 加密方式 | 预期输出 |
|---|---|---|---|
| `fat-single` | 单模块 fat jar | 直接加密主 jar | `RESULT=hello-WORLD-3` |
| `fat-multi` | 多模块 fat jar | 加密主 jar，`-libjars` 指定包内依赖 | `RESULT=calc=50,greet=AB` |
| `thin-single` | 单模块 thin jar | 直接加密主 jar | `RESULT=hello-WORLD-3` |
| `thin-multi` | 多模块 thin jar | 主 jar 与外置依赖**分别**加密 | `RESULT=calc=50,greet=AB` |

## 版本矩阵

用同一套业务代码（`GreetService.greet`），换不同 JDK 与 Spring Boot 版本，验证字节码版本与
嵌套 jar URL 形式的兼容性：

| 目录 | JDK | Spring Boot | class file major | 覆盖点 |
|---|---|---|---|---|
| `jdk8-boot2` | 8 | 2.6.3 | 52 | 老字节码 + `jar:file:` 嵌套 URL |
| `jdk17-boot3` | 17 | 3.5.5 | 61 | `nested:` 嵌套 URL |
| `jdk21-boot3` | 21 | 3.5.5 | 65 | 新字节码 + `nested:` 嵌套 URL |

脚本按目标版本自动定位 JDK（macOS 用 `java_home -v`，其他系统请设 `JAVA_HOME_8` /
`JAVA_HOME_17` / `JAVA_HOME_21` 环境变量）。编译与运行都用目标 JDK，确保 agent 真的
跑在该版本上。

> `jdk8-boot2` 曾暴露一个真实 bug：工具用 source/target 1.8 而非编译器的 release 8 构建，
> 导致字节码引用了 Java 9 才有的 `ByteBuffer.flip()` 协变返回，在 JDK 8 上抛
> `NoSuchMethodError`。根 pom 已改为 `maven.compiler.release=8` 修复。


## 各形态要点

### fat jar（`fat-single` / `fat-multi`）

依赖打进主 jar 的 `BOOT-INF/lib`。多模块时用 `-libjars` 指定要加密的依赖 jar 文件名：

```bash
java -jar classfinal-fatjar.jar \
  -file app.jar \
  -libjars your-lib-1.0.0.jar \
  -packages com.example \
  -pwd 123456 -Y
```

### thin jar（`thin-single` / `thin-multi`）

依赖外置到 `lib/`，主 jar 的 `BOOT-INF/lib` 为空，`-libjars` 不起作用。
必须**逐个**加密外置依赖，再用同一密码启动：

```bash
# 主 jar
java -jar classfinal-fatjar.jar -file thin-app.jar -packages com.example.app -pwd 123456 -Y

# 外置依赖，逐个加密后替换回 lib/
for j in lib/*.jar; do
  java -jar classfinal-fatjar.jar -file "$j" -packages com.example.lib -pwd 123456 -Y
  mv "${j%.jar}-encrypted.jar" "$j"
done

# 启动
java -javaagent:thin-app-encrypted.jar=-pwd 123456 \
     -Dloader.path=lib -jar thin-app-encrypted.jar
```

## 为什么用 `RESULT=...` 断言

示例里的业务方法体带字符串拼接与算术。若解密失败，方法体是**空壳桩**（返回 `null`/`0`），
输出就会变成 `RESULT=null` 或 `RESULT=calc=0,...`，脚本随即判定失败。
这样能区分「启动了」和「真的解密成功」。
