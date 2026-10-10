package net.roseboy.classfinal.util;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

/**
 * 生成"桩类"：把每个方法的方法体整体替换为一条最小的返回指令。
 *
 * <h3>为什么不用 javassist 的 {@code insert + rebuildStackMapIf6}</h3>
 * 原版 {@code ClassUtils.setBodyKeepParamInfos} 用 {@code CodeIterator.insertEx()}
 * 把桩指令<b>插入</b>到原方法体前面，原方法体（含大量分支）仍然留在 class 文件里。
 * 于是 javassist 必须为这段永不执行的代码重建 StackMapTable —— 而在 JDK 17 的
 * class file 61 上，它重建出的帧与实际分支目标对不上，加载时直接失败：
 *
 * <pre>
 * java.lang.VerifyError: Expecting a stack map frame
 *   Location: com/example/SomeClass.$deserializeLambda$(...)  @41: nop
 *   Reason:   Expected stackmap frame at this location.
 * </pre>
 *
 * 这里改成<b>整体替换</b>：
 * <ul>
 *   <li>丢弃原方法体的全部指令与代码级属性（StackMapTable / LineNumberTable /
 *       LocalVariableTable / 异常表 / 指令级类型注解）；</li>
 *   <li>只输出一条返回指令。桩方法没有任何分支，因此<b>不需要</b> StackMapTable，
 *       也就不存在帧不一致的可能。</li>
 * </ul>
 *
 * <h3>哪些东西必须保留（否则框架会崩）</h3>
 * 方法级注解、参数注解、{@code MethodParameters}、方法描述符、类级注解、
 * 字段与泛型签名都原样透传。Spring 的组件扫描是用 ASM 直接读磁盘上的 class
 * 字节做元数据判断的，保留这些才能让 {@code @RestController}/{@code @Bean}/
 * {@code @RequestParam} 等继续生效。
 *
 * <h3>为什么可以丢掉 LocalVariableTable</h3>
 * 本项目由 spring-boot-starter-parent 编译，默认开启 {@code -parameters}，
 * class 文件里带 {@code MethodParameters} 属性，Spring 6 走
 * {@code StandardReflectionParameterNameDiscoverer} 取参数名，不依赖调试表。
 */
public final class ClassStubber {

    private ClassStubber() {
    }

    /**
     * 返回替换方法体后的 class 字节。
     *
     * @param classBytes 原始 class 字节
     * @return 桩类字节
     */
    public static byte[] stub(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        // 不需要 COMPUTE_FRAMES / COMPUTE_MAXS：桩方法无分支，栈与局部变量数由我们自己给出
        ClassWriter cw = new ClassWriter(0);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                // 构造器必须调用 super()、静态初始化器要保留字段赋值，两者都不动
                if (name.startsWith("<")) {
                    return mv;
                }
                // 抽象方法与 native 方法本来就没有 Code 属性
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return mv;
                }
                return new StubMethodVisitor(mv, access, descriptor);
            }
        };
        // 不解析帧/不解析调试信息，纯粹做结构改写
        cr.accept(cv, ClassReader.SKIP_FRAMES);
        return cw.toByteArray();
    }

    /**
     * 只输出一条返回指令的方法访问器；其余回调全部吞掉。
     */
    private static final class StubMethodVisitor extends MethodVisitor {
        private final int access;
        private final String descriptor;
        private boolean emitted;

        StubMethodVisitor(MethodVisitor mv, int access, String descriptor) {
            super(Opcodes.ASM9, mv);
            this.access = access;
            this.descriptor = descriptor;
        }

        @Override
        public void visitCode() {
            if (emitted) {
                return;
            }
            emitted = true;
            mv.visitCode();
            switch (Type.getReturnType(descriptor).getSort()) {
                case Type.VOID:
                    mv.visitInsn(Opcodes.RETURN);
                    break;
                case Type.BOOLEAN:
                case Type.CHAR:
                case Type.BYTE:
                case Type.SHORT:
                case Type.INT:
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                    break;
                case Type.LONG:
                    mv.visitInsn(Opcodes.LCONST_0);
                    mv.visitInsn(Opcodes.LRETURN);
                    break;
                case Type.FLOAT:
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                    break;
                case Type.DOUBLE:
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                    break;
                default:
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitInsn(Opcodes.ARETURN);
                    break;
            }
            // max_stack=2 足够放下常量+返回；max_locals 按描述符算，保证 >= 真实入参槽位
            int locals = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            for (Type t : Type.getArgumentTypes(descriptor)) {
                locals += t.getSize();
            }
            mv.visitMaxs(2, locals);
            mv.visitEnd();
        }

        /* ---------- 以下：丢弃原方法体的一切 ---------- */

        @Override
        public void visitInsn(int opcode) {
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
        }

        @Override
        public void visitLabel(Label label) {
        }

        @Override
        public void visitLdcInsn(Object value) {
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        }

        /* 代码级调试/类型信息：桩代码里不再有对应的目标，必须一并丢掉，
           否则 ASM 会因为标注目标缺失而报错或产出非法属性。 */

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {
        }

        @Override
        public void visitLineNumber(int line, Label start) {
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            return null;
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                         String descriptor, boolean visible) {
            return null;
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                              Label[] start, Label[] end, int[] index,
                                                              String descriptor, boolean visible) {
            return null;
        }

        /* visitMaxs / visitEnd 已在上面的 visitCode 里输出过，这里吞掉后续调用 */
        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
        }

        @Override
        public void visitEnd() {
        }
    }
}
