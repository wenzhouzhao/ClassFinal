# ClassFinal（JDK 17 适配版）

> 本仓库是 [gitee.com/roseboy/classfinal](https://gitee.com/roseboy/classfinal) **1.2.1**（2020-04-29 最后一次提交，Apache-2.0）
> 的适配分支，目标是让它在 **JDK 17 / Spring Boot 3.5** 的多模块 fat jar 上真正可用。
> 上游原版在 JDK 17 下无法工作，见下文「为什么原版不能用」。

---

## 一、为什么原版不能用

原版的做法是：

1. 把每个类的**原始字节**加密后存到 `META-INF/.classes/<类全名>`；
2. 用 javassist 把磁盘上的 `.class` 的**方法体清空**，只留类名/注解/字段/方法签名；
3. 运行时 `CoreAgent` 通过 `ClassFileTransformer` 拦截类加载，从 `META-INF/.classes/` 解密换回原始字节。

第 2 步在 JDK 17 上有两个致命问题：

### 问题 1：`VerifyError: Expecting a stack map frame`

`ClassUtils.setBodyKeepParamInfos` 用 `CodeIterator.insertEx()` 把桩指令**插入**到原方法体
**前面**，原方法体（含大量分支）仍留在 class 文件里。于是 javassist 必须为这段永不执行的
代码重建 StackMapTable，而它在 class file 61 上重建出的帧与实际分支目标对不上：

```
java.lang.VerifyError: Expecting a stack map frame
  Location: com/example/SomeClass.$deserializeLambda$(...) @41: nop
  Reason:   Expected stackmap frame at this location.
```

上游最后一个版本（1.2.1）面向 Java 8 字节码，官方再无更新，社区分支也没能真正修好
（有个分支的做法是「跳过所有含 lambda 的类」，实测把 59.6% 的类排除在外）。

### 问题 2：Spring Boot 3.2+ 的 `nested:` URL 让运行时解密全部静默失败

`JarUtils.getRootPath` 按 `!` 截取 jar 路径，只处理了老格式：

```
Spring Boot <= 3.1 : jar:file:/path/app.jar!/BOOT-INF/lib/x.jar!/
Spring Boot >= 3.2 : jar:nested:/path/app.jar/!BOOT-INF/lib/x.jar!/
```

`nested:` 前缀没被剥掉，截出来的是 `nested:/path/app.jar/` 这种非法路径，
`META-INF/.classes` 永远查不到 → **一个类都不解密**（实测 0 次），
但应用还是能启动，直到某个 `@Bean` 方法返回了桩里的 `null`：

```
BeanNotOfRequiredTypeException: Bean named 'taskScheduler' is expected to be of type
'org.springframework.scheduling.TaskScheduler' but was actually of type
'org.springframework.beans.factory.support.NullBean'
```

这种「看起来启动了、实际全是空壳」的失败比直接崩溃更危险。

---

## 二、本分支做了什么

| # | 改动 | 说明 |
|---|---|---|
| 1 | 去掉 `oss-parent` 与 `nexus-staging-maven-plugin` | 发布到 Sonatype 用的，与本工具无关，且当前环境无法解析 |
| 2 | javassist `3.25.0-GA` → `3.30.2-GA` | 原版 javassist 读不了 JDK 17 的 class file 61 |
| 3 | 新增 `ClassStubber`（基于 ASM 9.7.1）替换 javassist 清方法体 | **核心修复**，见下 |
| 4 | `JarEncryptor.clearClassMethod` 失败即中止构建 | 原版吞异常并继续，导致「报告加密 N 个、实际改 M 个」 |
| 5 | `JarUtils.getRootPath` 兼容 `nested:` 协议 | 修复运行时解密全部失效 |
| 6 | 固定构建插件版本 | 弱网/离线环境必需 |
| 7 | 构建改用 `maven.compiler.release=8` | 原用 `source/target 1.8`，会按现代 JDK 的 API 签名编译，字节码里引用了 Java 9+ 才有的 `ByteBuffer.flip()` 协变返回，在 JDK 8 上抛 `NoSuchMethodError` |
| 8 | `classfinal-maven-plugin` 暂不参与构建 | 依赖 `maven-plugin-plugin`，先用 `classfinal-fatjar` 命令行驱动 |

### 核心修复：方法体「整体替换」而不是「插入」

`ClassStubber` 用 ASM 重写 class：

- **丢弃**每个方法原本的全部指令与代码级属性（StackMapTable / LineNumberTable /
  LocalVariableTable / 异常表 / 指令级类型注解）；
- **只输出一条返回指令**（按返回类型选 `return` / `ireturn` / `lreturn` / `areturn` …）。
  桩方法没有任何分支，因此**不需要** StackMapTable，也就不存在帧不一致的可能。

必须保留、且确实原样透传的东西：方法级注解、参数注解、`MethodParameters`、
方法描述符、类级注解、字段与泛型签名。Spring 的组件扫描是直接用 ASM 读磁盘上的
class 字节做元数据判断的，保留这些才能让 `@RestController` / `@Bean` / `@RequestParam`
继续生效。

`<init>` 与 `<clinit>` **不动**（构造器必须调用 `super()`，静态初始化器要保留字段赋值）。

---

## 三、构建

需要 JDK 8+ 编译、Maven：

```bash
mvn -s <你的 settings.xml> clean package -DskipTests -pl classfinal-core
```

> 若网络受限，先确保本地仓库有 `org.javassist:javassist:3.30.2-GA`
> 与 `org.ow2.asm:asm:9.7.1`。

产物：`classfinal-core/target/classfinal-core-1.2.1.jar`

`classfinal-fatjar` 的 shade 阶段需要 `commons-compress`，离线时可直接用
`classpath` 方式运行命令行（见下）。

---

## 四、使用

### 命令行的 classpath 方式（无需 shade）

```bash
JV=~/.m2/repository/org/javassist/javassist/3.30.2-GA/javassist-3.30.2-GA.jar
ASM=~/.m2/repository/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar
CP="classfinal-fatjar/target/classes:classfinal-core/target/classes:$JV:$ASM"

java -cp "$CP" net.roseboy.classfinal.Main \
  -file /path/app-1.0.0.jar \
  -libjars 'module-a-1.0.0.jar,module-b-1.0.0.jar,module-c-1.0.0.jar,...' \
  -packages 'com.yourcompany' \
  -exclude 'com.yourcompany.Application' \
  -pwd '<密码>' -Y
```

产物为同目录下的 `*-encrypted.jar`。

> 注意 **多模块工程必须写 `-libjars`**：业务代码不在 `BOOT-INF/classes`（那里只有启动类），
> 而在 `BOOT-INF/lib/*.jar` 里。不写 `-libjars` 只会加密到 1 个类。

### 启动加密包

```bash
java -javaagent:app-encrypted.jar=-pwd '<密码>' -jar app-encrypted.jar
```

`app.jar` 的清单里会写入 `Premain-Class: net.roseboy.classfinal.CoreAgent`。

> 注意 **不要用 `head -c 64K | grep Premain-Class` 判断是否是加密包**：
> fat jar 里 `META-INF/MANIFEST.MF` 是 DEFLATE 压缩存放的，明文 grep 搜不到。
> zip 的**条目名**是明文存在本地文件头与中央目录里的，改搜
> `net/roseboy/classfinal/CoreAgent.class` 才可靠。

### 与容器化部署的对接

若构建脚本按产物名自动识别 `*-encrypted.jar`，可在镜像构建时把
`-javaagent` 注入入口脚本；配置目录建议由外挂卷提供
（`--spring.config.location=file:/app/config/`）。

---

## 五、实测结果（Spring Boot 3.5.5 / JDK 17.0.20）

| 项目 | 原版 1.2.1 | 本分支 |
|---|---|---|
| 方法体清空数量 | 1225 / 1508（81.2%，其余静默漏掉） | **1508 / 1508（100%）** |
| 启动 | `VerifyError` | 正常启动 |
| 加解密往返 | — | **字节级一致**（解密结果与原始 class 的 md5 相同） |
| 与未加密包的失败点对比 | — | 完全一致（同为 `UnknownHostException: db`） |
| 全栈 E2E | — | 容器 healthy，`Started Application in 22.259 s` |
| 健康检查 | — | `/actuator/health` = `{"status":"UP"}`，首页 HTTP 200 |

加密后 jar 体积：399 MB → 406 MB。

---

## 六、兼容性矩阵：哪些组合验证过

### 结论先讲：不要按维度拉分支，一套代码 + 参数化即可

| 维度 | 差异点 | 怎么覆盖 | 需要分支吗 |
|---|---|---|---|
| **JDK 版本** | class 文件版本号 | ASM 直接处理 major 52~68（Java 8~24） | 不需要 |
| **Spring Boot 版本** | 嵌套 jar 的 URL 形式在 Boot 3.2 变过 | `JarUtils.getRootPath` 同时兼容 `jar:file:` 与 `jar:nested:` | 不需要 |
| **单模块 / 多模块** | 业务类在 `BOOT-INF/classes` 还是在 `BOOT-INF/lib/*.jar` | `libjars` 参数：单模块留空、多模块列出模块 jar | 不需要 |
| **包名 / 排除规则** | 每个项目不同 | `packages` / `excludes` 参数 | 不需要 |
| **fat jar / war** | 路径前缀不同 | `getRootPath` 兼容 `WEB-INF` 与 `!` | 不需要（war 未实测） |

**为什么不按分支管理**：各维度的实际代码差异接近于零，分叉只会带来 95% 的重复代码。
更关键的是维护成本 —— 一旦按维度拉分支，任何修复（比如这次的两个根因修复：
桩类栈映射帧、`nested:` URL 导致解密失效）都得往 N 个分支各移植一遍，
漏掉一个就留下一个「静默漏加密」的版本，而那种版本从外部完全看不出来。
上游 ClassFinal 的死因就是「没人维护」，多分支只会加速它。

### 实测记录

| 组合 | 状态 | 证据 |
|---|---|---|
| **JDK 17 + Spring Boot 3.5.5 + 多模块（12 模块）** | 实测通过 | 1509/1509 = 100%；全栈 E2E 容器 healthy |
| **单模块**（业务类在 `BOOT-INF/classes`） | 实测通过 | 32/32 = 100%；加解密往返字节一致 |
| **JDK 8 / 11 / 17 字节码**（major 52/55/61） | 实测通过 | 桩类均通过校验器并能正常加载 |
| **JDK 21 字节码**（major 65） | 实测通过 | `classfinal-demo/jdk21-boot3` 加密运行正常 |
| JDK 22~24 字节码（major 66~68） | 版本门通过 | ASM 接受；未在真实工程上跑过 |
| JDK 25+（major ≥ 69） | 不支持 | ASM 9.7.1 报 `Unsupported class file major version`；需升 ASM |
| **agent 运行在 JDK 8 上** | 实测通过 | `classfinal-demo/jdk8-boot2`：agent 在 JDK 8 上加载并解密成功 |
| **Spring Boot ≤ 3.1 / 1.x 的 URL 形式** | 单元测试通过 | `getRootPath("jar:file:/opt/app.jar!/...")` → `/opt/app.jar` |
| war（`java -jar` / Tomcat 部署） | 未实测 | 路径解析已修正，整体流程未跑过 |
| `cfgfiles`（加密配置文件） | 不支持 | 靠**硬编码行号**往 Spring 的 `ClassPathResource` 里插代码，与 Spring 版本强耦合 |

### 要加新组合时怎么做（而不是拉分支）

1. 先判断是新「维度」还是新「参数」——绝大多数情况只是参数。
2. 新字节码版本 → 升 ASM 依赖（一个版本号）。
3. 新框架的 URL / 目录布局 → 在 `JarUtils.getRootPath` 里加容错分支。
4. **必须同时补回归验证**：覆盖率校验脚本要能在新布局上正确统计。单模块的支持就是
   这么补出来的 —— 旧的校验只扫 `BOOT-INF/lib/*.jar`，对单模块会**漏掉一整套类**
   却报告「通过」。

### 维护陷阱：`Const.CLASSFINAL_FILES`

加密时会把 agent 需要的类按**文件名白名单**拷进目标 jar（见 `Const.CLASSFINAL_FILES`）。
以后如果给运行时那条链路（`CoreAgent` / `AgentTransformer` / `JarDecryptor` / `EncryptUtils` …）
新增了类却忘记加进白名单，加密包在目标机器上会以 `NoClassDefFoundError` 启动失败，
**而构建阶段一切正常**，非常难查。
（只用于构建期的 `ClassStubber` 不在这个白名单里 —— 它不进包，这一点是对的。）

## 七、已知限制与注意事项

1. **`<init>` / `<clinit>` 不加密**：构造器与静态初始化器的代码仍然可读。
   这是刻意的——构造器必须调用 `super()`，静态初始化器要完成字段赋值。
2. **丢弃了调试表**：桩类不含 `LocalVariableTable`。若工程由
   `spring-boot-starter-parent` 编译，默认开启 `-parameters`，Spring 6 走
   `StandardReflectionParameterNameDiscoverer` 取参数名，因此无影响。
   若换到没开 `-parameters` 的工程，需要重新评估参数名解析。
3. **防护强度是「提高门槛」而非「不可破」**：密码必须内嵌在启动参数里，
   有能力的攻击者仍可通过内存 dump 或 hook 拿到字节码。
4. **`cfgfiles`（加密配置文件）在本分支未验证**：配置文件由外置配置提供时
   不使用该特性；原版那套「改写 Spring `ClassPathResource` 注入解密」在 Spring 6 上风险较高。
5. **WAR 部署未验证**：仅验证了 Spring Boot fat jar。
6. **`-debug` 在 `Main` 里原本不生效**（上游 bug），调试请用 `-javaagent` 侧的 `-debug`。

---

## 八、打包形态兼容性：fat jar 与 thin jar

### 结论：thin 包同样支持

运行时解密靠 `AgentTransformer`：对每个被加载的类取 `CodeSource.getLocation()`，
用 `JarUtils.getRootPath()` 解析出「根 jar」，再从**那个 jar** 的
`META-INF/.classes/<类名>` 解密。因此：

- **app 自身类**（`BOOT-INF/classes`）→ 根 = 主 jar；
- **外置依赖类**（thin 包里的 `lib/x.jar`）→ 根 = 该外置 jar。

两种形态天然都支持。实测（Spring Boot 3.5.5 / JDK 21，thin 包用 `layout=ZIP`
即 `PropertiesLauncher`，配合 `-Dloader.path`）：

| 场景 | 结果 |
|---|---|
| 单模块 fat jar | 通过 |
| 多模块 fat jar（`-libjars` 加密包内依赖） | 通过 |
| 单模块 thin jar | 通过 |
| 多模块 thin jar（app + 外置 lib 均加密） | 通过 |
| 反向：不挂 `-javaagent` | 无输出（`main` 是空壳桩，Spring 根本没启动）→ 证明加密真实生效 |
| 反向：错误密码 | `ERROR: Startup failed, invalid password.` 退出 |
| 字节码对比 | 加密包 `add()` = `iconst_0; ireturn`（空壳）；明文 = 真实 `iadd/imul` |

### thin 包怎么加密

thin 包把依赖外置到 `lib/`，而 `-libjars` 只处理包**内部**的 `BOOT-INF/lib`、`WEB-INF/lib`，
对 thin 包这两个目录是空的 —— 所以外置依赖**必须逐个 jar 单独加密**，再用同一密码启动：

```bash
# 1) 加密主 jar（app 自身类）
java -jar classfinal-fatjar.jar -file thin-app.jar -packages com.example.app -pwd 123456 -Y

# 2) 逐个加密外置依赖，替换回 lib/
for j in lib/*.jar; do
  java -jar classfinal-fatjar.jar -file "$j" -packages com.example.lib -pwd 123456 -Y
  mv "${j%.jar}-encrypted.jar" "$j"
done

# 3) 启动（同一密码 + -Dloader.path 指向 lib/）
java -javaagent:thin-app-encrypted.jar=-pwd 123456 \
     -Dloader.path=lib -jar thin-app-encrypted.jar
```

### 注意事项

1. 外置依赖需**逐个**加密并替换，不是一条命令完成 —— 这是 thin 包与 fat 包最大的操作差别。
2. 单独加密一个外置 jar 时，classfinal 会往它的 manifest 写入 `Premain-Class`，
   并注入 `net/roseboy/*` 的 agent 类。功能无影响，但该 jar 会带上这些额外内容。
3. `-Dloader.path` 仅在 `layout=ZIP`（`PropertiesLauncher`）下生效；用默认 `JarLauncher`
   时外置依赖不会被加载 —— 这是 Spring Boot 的约束，与本工具无关。
4. 密码 hash 从**主 jar** 读取，故主 jar 必须加密且带 agent；外置 jar 只承载自己的加密类。

`classfinal-demo/` 目录下有四种打包形态的可运行示例（fat/thin × 单/多模块）、几个
JDK / Spring Boot 版本组合（JDK 8+Boot 2.6.3、JDK 17/21+Boot 3.5.5），以及一键验证脚本
（`classfinal-demo/run-all.sh`）。

---

# ClassFinal

## 介绍
ClassFinal是一款java class文件安全加密工具，支持直接加密jar包或war包，无需修改任何项目代码，兼容spring-framework；可避免源码泄漏或字节码被反编译。

##### Gitee: https://gitee.com/roseboy/classfinal

## 项目模块说明
* **classfinal-core:** ClassFinal的核心模块，几乎所有加密的代码都在这里；
* **classfinal-fatjar:** ClassFinal打包成独立运行的jar包；
* **classfinal-maven-plugin:** ClassFinal加密的maven插件；

## 功能特性
* 无需修改原项目代码，只要把编译好的jar/war包用本工具加密即可。
* 运行加密项目时，无需求修改tomcat，spring等源代码。
* 支持普通jar包、springboot jar包以及普通java web项目编译的war包。
* 支持spring framework、swagger等需要在启动过程中扫描注解或生成字节码的框架。
* 支持maven插件，添加插件后在打包过程中自动加密。
* 支持加密WEB-INF/lib或BOOT-INF/lib下的依赖jar包。
* 支持绑定机器，项目加密后只能在特定机器运行。
* 支持加密springboot的配置文件。

## 环境依赖
JDK 1.8 +

## 使用说明

### 下载
[点此下载](https://repo1.maven.org/maven2/net/roseboy/classfinal-fatjar/1.2.1/classfinal-fatjar-1.2.1.jar)

### 加密

执行以下命令

```sh
java -jar classfinal-fatjar.jar -file yourpaoject.jar -libjars a.jar,b.jar -packages com.yourpackage,com.yourpackage2 -exclude com.yourpackage.Main -pwd 123456 -Y
```

```text
参数说明
-file        加密的jar/war完整路径
-packages    加密的包名(可为空,多个用","分割)
-libjars     jar/war包lib下要加密jar文件名(可为空,多个用","分割)
-cfgfiles    需要加密的配置文件，一般是classes目录下的yml或properties文件(可为空,多个用","分割)
-exclude     排除的类名(可为空,多个用","分割)
-classpath   外部依赖的jar目录，例如/tomcat/lib(可为空,多个用","分割)
-pwd         加密密码，如果是#号，则使用无密码模式加密
-code        机器码，在绑定的机器生成，加密后只可在此机器上运行
-Y           无需确认，不加此参数会提示确认以上信息
```

结果: 生成 yourpaoject-encrypted.jar，这个就是加密后的jar文件；加密后的文件不可直接执行，需要配置javaagent。

> 注:
> 以上示例是直接用参数执行，也可以直接执行 java -jar classfinal-fatjar.jar按照步骤提示输入信息完成加密。

### maven插件方式

在要加密的项目pom.xml中加入以下插件配置,目前最新版本是：1.2.1。
```xml
<plugin>
    <!-- https://gitee.com/roseboy/classfinal -->
    <groupId>net.roseboy</groupId>
    <artifactId>classfinal-maven-plugin</artifactId>
    <version>${classfinal.version}</version>
    <configuration>
        <password>000000</password><!--加密打包之后pom.xml会被删除，不用担心在jar包里找到此密码-->
        <packages>com.yourpackage,com.yourpackage2</packages>
        <cfgfiles>application.yml</cfgfiles>
        <excludes>org.spring</excludes>
        <libjars>a.jar,b.jar</libjars>
    </configuration>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>classFinal</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```
运行mvn package时会在target下自动加密生成yourpaoject-encrypted.jar。

maven插件的参数名称与直接运行的参数相同，请参考上节的参数说明。

### 无密码模式

加密时-pwd参数设为#，启动时可不用输入密码；
如果是war包，启动时指定参数 -nopwd，跳过输密码过程。

### 机器绑定

机器绑定只允许加密的项目在特定的机器上运行；

在需要绑定的机器上执行以下命令，生成机器码
```sh
java -jar classfinal-fatjar.jar -C
```
加密时用-code指定机器码。机器绑定可同时支持机器码+密码的方式加密。


### 启动加密后的jar

加密后的项目需要设置javaagent来启动，项目在启动过程中解密class，完全内存解密，不留下任何解密后的文件。

解密功能已经自动加入到 yourpaoject-encrypted.jar中，所以启动时-javaagent与-jar相同，不需要额外的jar包。

启动jar项目执行以下命令：

```sh
java -javaagent:yourpaoject-encrypted.jar='-pwd 0000000' -jar yourpaoject-encrypted.jar

//参数说明
// -pwd      加密项目的密码  
// -pwdname  环境变量中密码的名字
```

或者不加pwd参数直接启动，启动后在控制台里输入密码，推荐使用这种方式：

```sh
java -javaagent:yourpaoject-encrypted.jar -jar yourpaoject-encrypted.jar
```
~~使用nohup命令启动时，如果系统支持gui，会弹出输入密码的界面，如果是纯命令行下，不支持gui，则需要在同级目录下的classfinal.txt或yourpaoject-encrypted.classfinal.txt中写入密码，项目读取到密码后会清空此文件。~~

密码读取顺序已经改为：参数获取密码||环境变量获取密码||密码文件获取密码||控制台输入密码||GUI输入密码||退出


### tomcat下运行加密后的war

将加密后的war放在tomcat/webapps下，
tomcat/bin/catalina 增加以下配置:

```sh
//linux下 catalina.sh
CATALINA_OPTS="$CATALINA_OPTS -javaagent:classfinal-fatjar.jar='-pwd 0000000'";
export CATALINA_OPTS;

//win下catalina.bat
set JAVA_OPTS="-javaagent:classfinal-fatjar.jar='-pwd 000000'"

//参数说明 
// -pwd      加密项目的密码  
// -nopwd    无密码加密时启动加上此参数，跳过输密码过程
// -pwdname  环境变量中密码的名字
```

-------------------------

> 本工具使用AES算法加密class文件，密码是保证不被破解的关键，请保存好密码，请勿泄漏。

> 密码一旦忘记，项目不可启动且无法恢复，请牢记密码。

> 本工具加密后，原始的class文件并不会完全被加密，只是方法体被清空，保留方法参数、注解等信息，这是为了兼容spring，swagger等扫描注解的框架；
方法体被清空后，反编译者只能看到方法名和注解，看不到方法的具体内容；当class被classloader加载时，真正的方法体会被解密注入。

> 为了保证项目在运行时的安全，启动jvm时请加参数:  -XX:+DisableAttachMechanism 。


## 版本说明
* v1.2.1 bug修复
* v1.2.0 packages、libjars、cfgfiles、exclude 参数增加通配符功能
* v1.1.7 支持加密springboot的配置文件；增加环境变量中读取密码
* v1.1.6 增加机器绑定功能
* v1.1.5 增加无密码加密方式，启动无需输密码，但是并不安全
* v1.1.4 纯命令行下运行jar时，从配置文件中读取密码，读取后清空文件
* v1.1.3 加入输入密码的弹框
* v1.1.2 修复windows下加密后不能启动的问题
* v1.1.1 启动jar时在控制台输入密码，无需将密码放在参数中
* v1.1.0 加密jar包时将解密代码加入加密后的jar包，无需使用多余的jar文件
* v1.0.0 第一个正式版发布


## 协议声明
[Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0)
