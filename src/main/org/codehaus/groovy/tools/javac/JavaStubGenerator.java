/*
 * Copyright 2003-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.tools.javac;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.Verifier;
import org.codehaus.groovy.tools.Utilities;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class JavaStubGenerator
{
    private boolean java5 = false;
    private boolean requireSuperResolved = false;
    private File outputPath;
    private List<String> toCompile = new ArrayList<String>();
    private ArrayList<MethodNode> propertyMethods = new ArrayList<MethodNode>();
    private ArrayList<ConstructorNode> constructors = new ArrayList<ConstructorNode>();

    public JavaStubGenerator(final File outputPath, final boolean requireSuperResolved, final boolean java5) {
        this.outputPath = outputPath;
        this.requireSuperResolved = requireSuperResolved;
        this.java5 = java5;
        outputPath.mkdirs();
    }

    public JavaStubGenerator(final File outputPath) {
        this(outputPath, false, false);
    }

    private void mkdirs(File parent, String relativeFile) {
        int index = relativeFile.lastIndexOf('/');
        if (index==-1) return;
        File dir = new File(parent,relativeFile.substring(0,index));
        dir.mkdirs();
    }

    public void generateClass(ClassNode classNode) throws FileNotFoundException {
        // Only attempt to render our self if our super-class is resolved, else wait for it
        if (requireSuperResolved && !classNode.getSuperClass().isResolved()) {
            return;
        }

        // owner should take care for us
        if (classNode instanceof InnerClassNode)
            return;

        // don't generate stubs for private classes, as they are only visible in the same file
        if ((classNode.getModifiers() & Opcodes.ACC_PRIVATE) != 0) return;

        String fileName = classNode.getName().replace('.', '/');
        mkdirs(outputPath,fileName);
        toCompile.add(fileName);

        File file = new File(outputPath, fileName + ".java");
        FileOutputStream fos = new FileOutputStream(file);
        PrintWriter out = new PrintWriter(fos);

        try {
            String packageName = classNode.getPackageName();
            if (packageName != null) {
                out.println("package " + packageName + ";\n");
            }

            genImports(classNode, out);

            genClassInner(classNode, out);

        } finally {
            try {
                out.close();
            } catch (Exception e) {
                // ignore
            }
            try {
                fos.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void genClassInner(ClassNode classNode, PrintWriter out) throws FileNotFoundException {
        if (classNode instanceof InnerClassNode && ((InnerClassNode) classNode).isAnonymous()) {
            // if it is an anonymous inner class, don't generate the stub code for it.
            return;
        }
        try {
            Verifier verifier = new Verifier() {
                public void addCovariantMethods(ClassNode cn) {}
                protected void addTimeStamp(ClassNode node) {}
                protected void addInitialization(ClassNode node) {}
                protected void addPropertyMethod(MethodNode method) {
                    propertyMethods.add(method);
                }
                protected void addReturnIfNeeded(MethodNode node) {}
                protected void addMethod(ClassNode node, boolean shouldBeSynthetic, String name, int modifiers, ClassNode returnType, Parameter[] parameters, ClassNode[] exceptions, Statement code) {
                    propertyMethods.add(new MethodNode(name, modifiers, returnType, parameters, exceptions, code));
                }
                protected void addConstructor(Parameter[] newParams, ConstructorNode ctor, Statement code, ClassNode node) {
                    constructors.add(new ConstructorNode(ctor.getModifiers(), newParams, ctor.getExceptions(), code));
                }
                protected void addDefaultParameters(DefaultArgsAction action, MethodNode method) {
                    final Parameter[] parameters = method.getParameters();
                    final Expression [] saved = new Expression[parameters.length];
                    for (int i = 0; i < parameters.length; i++) {
                        if (parameters[i].hasInitialExpression())
                            saved[i] = parameters[i].getInitialExpression();
                    }
                    super.addDefaultParameters(action, method);
                    for (int i = 0; i < parameters.length; i++) {
                        if (saved[i] != null)
                            parameters[i].setInitialExpression(saved[i]);
                    }
                }
            };
            verifier.visitClass(classNode);

            boolean isInterface = classNode.isInterface();
            boolean isEnum = (classNode.getModifiers() & Opcodes.ACC_ENUM) !=0;
            printModifiers(out, classNode.getModifiers()
                    & ~(isInterface ? Opcodes.ACC_ABSTRACT : 0));

            if (isInterface) {
                out.print ("interface ");
            } else if (isEnum) {
                out.print ("enum ");
            } else {
                out.print ("class ");
            }

            String className = classNode.getNameWithoutPackage();
            if (classNode instanceof InnerClassNode)
                className = className.substring(className.lastIndexOf("$")+1);
            out.println(className);
            writeGenericsBounds(out, classNode, true);

            ClassNode superClass = classNode.getUnresolvedSuperClass(false);

            if (!isInterface && !isEnum) {
                out.print("  extends ");
                printType(superClass,out);
            }

            ClassNode[] interfaces = classNode.getInterfaces();
            if (interfaces != null && interfaces.length > 0) {
                if (isInterface) {
                    out.println("  extends");
                } else {
                    out.println("  implements");
                }
                for (int i = 0; i < interfaces.length - 1; ++i) {
                    out.print("    ");
                    printType(interfaces[i], out);
                    out.print(",");
                }
                out.print("    ");
                printType(interfaces[interfaces.length - 1],out);
            }
            out.println(" {");

            genFields(classNode, out);
            genMethods(classNode, out, isEnum);

            for (Iterator<InnerClassNode> inner = classNode.getInnerClasses(); inner.hasNext();) {
                // GROOVY-4004: Clear the methods from the outer class so that they don't get duplicated in inner ones
                propertyMethods.clear();
                constructors.clear();
                genClassInner(inner.next(), out);
            }

            out.println("}");
        }
        finally {
            propertyMethods.clear();
            constructors.clear();
        }
    }

    private void genMethods(ClassNode classNode, PrintWriter out, boolean isEnum) {
        if (!isEnum) getConstructors(classNode, out);

        @SuppressWarnings("unchecked")
        List<MethodNode> methods = (List)propertyMethods.clone();
        methods.addAll(classNode.getMethods());
        for (MethodNode method : methods) {
            if (isEnum && method.isSynthetic()) {
                // skip values() method and valueOf(String)
                String name = method.getName();
                Parameter[] params = method.getParameters();
                if (name.equals("values") && params.length == 0) continue;
                if (name.equals("valueOf") &&
                        params.length == 1 &&
                        params[0].getType().equals(ClassHelper.STRING_TYPE)) {
                    continue;
                }
            }
            genMethod(classNode, method, out);
        }
    }

    private void getConstructors(ClassNode classNode, PrintWriter out) {
        List<ConstructorNode> constrs = (List<ConstructorNode>) constructors.clone();
        constrs.addAll(classNode.getDeclaredConstructors());
        if (constrs != null)
            for (ConstructorNode constr : constrs) {
                genConstructor(classNode, constr, out);
            }
    }

    private void genFields(ClassNode classNode, PrintWriter out) {
        List<FieldNode> fields = classNode.getFields();
        if (fields == null) return;
        List<FieldNode> enumFields = new ArrayList<FieldNode>(fields.size());
        List<FieldNode> normalFields = new ArrayList<FieldNode>(fields.size());
        for (FieldNode field : fields) {
            boolean isSynthetic = (field.getModifiers() & Opcodes.ACC_SYNTHETIC) != 0;
            if (field.isEnum()) {
                enumFields.add(field);
            } else if (!isSynthetic) {
                normalFields.add(field);
            }
        }
        genEnumFields(enumFields, out);
        for (FieldNode normalField : normalFields) {
            genField(normalField, out);
        }
    }


    private void genEnumFields(List<FieldNode> fields, PrintWriter out) {
        if (fields.size()==0) return;
        boolean first = true;
        for (FieldNode field : fields) {
            if (!first) {
                out.print(", ");
            } else {
                first = false;
            }
            out.print(field.getName());
        }
        out.println(";");
    }

    private void genField(FieldNode fieldNode, PrintWriter out) {
        if ((fieldNode.getModifiers()&Opcodes.ACC_PRIVATE)!=0) return;
        printModifiers(out, fieldNode.getModifiers());

        printType(fieldNode.getType(), out);

        out.print(" ");
        out.print(fieldNode.getName());
        out.println(";");
    }

    private ConstructorCallExpression getConstructorCallExpression(
            ConstructorNode constructorNode) {
        Statement code = constructorNode.getCode();
        if (!(code instanceof BlockStatement))
            return null;

        BlockStatement block = (BlockStatement) code;
        List stats = block.getStatements();
        if (stats == null || stats.size() == 0)
            return null;

        Statement stat = (Statement) stats.get(0);
        if (!(stat instanceof ExpressionStatement))
            return null;

        Expression expr = ((ExpressionStatement) stat).getExpression();
        if (!(expr instanceof ConstructorCallExpression))
            return null;

        return (ConstructorCallExpression) expr;
    }

    private void genConstructor(ClassNode clazz, ConstructorNode constructorNode, PrintWriter out) {
        // printModifiers(out, constructorNode.getModifiers());

        out.print("public "); // temporary hack
        String className = clazz.getNameWithoutPackage();
        if (clazz instanceof InnerClassNode)
            className = className.substring(className.lastIndexOf("$")+1);
        out.println(className);

        printParams(constructorNode, out);

        ConstructorCallExpression constrCall = getConstructorCallExpression(constructorNode);
        if (constrCall == null || !constrCall.isSpecialCall()) {
            out.println(" {}");
        } else {
            out.println(" {");
            genSpecialConstructorArgs(out, constructorNode, constrCall);
            out.println("}");
        }
    }

    private Parameter[] selectAccessibleConstructorFromSuper(ConstructorNode node) {
        ClassNode type = node.getDeclaringClass();
        ClassNode superType = type.getSuperClass();

        for (ConstructorNode c : superType.getDeclaredConstructors()) {
            // Only look at things we can actually call
            if (c.isPublic() || c.isProtected()) {
                return c.getParameters();
            }
        }

        // fall back for parameterless constructor 
        if (superType.isPrimaryClassNode()) {
            return Parameter.EMPTY_ARRAY;
        }

        return null;
    }

    private void genSpecialConstructorArgs(PrintWriter out, ConstructorNode node, ConstructorCallExpression constrCall) {
        // Select a constructor from our class, or super-class which is legal to call,
        // then write out an invoke w/nulls using casts to avoid ambiguous crapo

        Parameter[] params = selectAccessibleConstructorFromSuper(node);
        if (params != null) {
            out.print("super (");

            for (int i=0; i<params.length; i++) {
                printDefaultValue(out, params[i].getType());
                if (i + 1 < params.length) {
                    out.print(", ");
                }
            }

            out.println(");");
            return;
        }

        // Otherwise try the older method based on the constructor's call expression
        Expression arguments = constrCall.getArguments();

        if (constrCall.isSuperCall()) {
            out.print("super(");
        }
        else {
            out.print("this(");
        }

        // Else try to render some arguments
        if (arguments instanceof ArgumentListExpression) {
            ArgumentListExpression argumentListExpression = (ArgumentListExpression) arguments;
            List<Expression> args = argumentListExpression.getExpressions();

            for (Expression arg : args) {
                if (arg instanceof ConstantExpression) {
                    ConstantExpression expression = (ConstantExpression) arg;
                    Object o = expression.getValue();

                    if (o instanceof String) {
                        out.print("(String)null");
                    } else {
                        out.print(expression.getText());
                    }
                } else {
                    ClassNode type = getConstructorArgumentType(arg, node);
                    printDefaultValue(out, type);
                }

                if (arg != args.get(args.size() - 1)) {
                    out.print(", ");
                }
            }
        }

        out.println(");");
    }

    private ClassNode getConstructorArgumentType(Expression arg, ConstructorNode node) {
        if (!(arg instanceof VariableExpression)) return arg.getType();
        VariableExpression vexp = (VariableExpression) arg;
        String name = vexp.getName();
        for (Parameter param : node.getParameters()) {
            if (param.getName().equals(name)) {
                return param.getType();
            }
        }
        return vexp.getType();
    }

    private void genMethod(ClassNode clazz, MethodNode methodNode, PrintWriter out) {
        if (methodNode.getName().equals("<clinit>")) return;
        if (methodNode.isPrivate() || !Utilities.isJavaIdentifier(methodNode.getName())) return;

        if (!clazz.isInterface()) printModifiers(out, methodNode.getModifiers());

        writeGenericsBounds(out, methodNode.getGenericsTypes());
        out.print(" ");
        printType(methodNode.getReturnType(), out);
        out.print(" ");
        out.print(methodNode.getName());

        printParams(methodNode, out);

        ClassNode[] exceptions = methodNode.getExceptions();
        for (int i=0; i<exceptions.length; i++) {
            ClassNode exception = exceptions[i];
            if (i==0) {
                out.print("throws ");
            } else {
                out.print(", ");
            }
            printType(exception,out);
        }

        if ((methodNode.getModifiers() & Opcodes.ACC_ABSTRACT) != 0) {
            out.println(";");
        } else {
            out.print(" { ");
            ClassNode retType = methodNode.getReturnType();
            printReturn(out, retType);
            out.println("}");
        }
    }

    private void printReturn(PrintWriter out, ClassNode retType) {
        String retName = retType.getName();
        if (!retName.equals("void")) {
            out.print("return ");

            printDefaultValue(out, retType);

            out.print(";");
        }
    }

    private void printDefaultValue(PrintWriter out, ClassNode type) {
        if (type.redirect()!=ClassHelper.OBJECT_TYPE) {
            out.print("(");
            printType(type,out);
            out.print(")");
        }

        if (ClassHelper.isPrimitiveType(type)) {
            if (type==ClassHelper.boolean_TYPE){
                out.print("false");
            } else {
                out.print("0");
            }
        } else {
            out.print("null");
        }
    }

    private void printType(ClassNode type, PrintWriter out) {
        if (type.isArray()) {
            printType(type.getComponentType(),out);
            out.print("[]");
        } else if (java5 && type.isGenericsPlaceHolder()) {
            out.print(type.getGenericsTypes()[0].getName());
        } else {
            writeGenericsBounds(out,type,false);
        }
    }

    private void printTypeName(ClassNode type, PrintWriter out) {
        if (ClassHelper.isPrimitiveType(type)) {
            if (type==ClassHelper.boolean_TYPE) {
                out.print("boolean");
            } else if (type==ClassHelper.char_TYPE) {
                out.print("char");
            } else if (type==ClassHelper.int_TYPE) {
                out.print("int");
            } else if (type==ClassHelper.short_TYPE) {
                out.print("short");
            } else if (type==ClassHelper.long_TYPE) {
                out.print("long");
            } else if (type==ClassHelper.float_TYPE) {
                out.print("float");
            } else if (type==ClassHelper.double_TYPE) {
                out.print("double");
            } else if (type==ClassHelper.byte_TYPE) {
                out.print("byte");
            } else {
                out.print("void");
            }
        } else {
            out.print(type.redirect().getName().replace('$', '.'));
        }
    }


    private void writeGenericsBounds(PrintWriter out, ClassNode type, boolean skipName) {
        if (!skipName) printTypeName(type,out);
        if (!java5) return;
        if (!ClassHelper.isCachedType(type)) {
            writeGenericsBounds(out,type.getGenericsTypes());
        }
    }

    private void writeGenericsBounds(PrintWriter out, GenericsType[] genericsTypes) {
        if (genericsTypes==null || genericsTypes.length==0) return;
        out.print('<');
        for (int i = 0; i < genericsTypes.length; i++) {
            if (i!=0) out.print(", ");
            writeGenericsBounds(out,genericsTypes[i]);
        }
        out.print('>');
    }

    private void writeGenericsBounds(PrintWriter out, GenericsType genericsType) {
        if (genericsType.isPlaceholder()) {
            out.print(genericsType.getName());
        } else {
            printType(genericsType.getType(),out);
        }

        ClassNode[] upperBounds = genericsType.getUpperBounds();
        ClassNode lowerBound = genericsType.getLowerBound();
        if (upperBounds != null) {
            out.print(" extends ");
            for (int i = 0; i < upperBounds.length; i++) {
                printType(upperBounds[i], out);
                if (i + 1 < upperBounds.length) out.print(" & ");
            }
        } else if (lowerBound != null) {
            out.print(" super ");
            printType(lowerBound, out);
        }
    }

    private void printParams(MethodNode methodNode, PrintWriter out) {
        out.print("(");
        Parameter[] parameters = methodNode.getParameters();

        if (parameters != null && parameters.length != 0) {
            for (int i = 0; i != parameters.length; ++i) {
                printType(parameters[i].getType(), out);

                out.print(" ");
                out.print(parameters[i].getName());

                if (i + 1 < parameters.length) {
                    out.print(", ");
                }
            }
        }

        out.print(")");
    }

    private void printModifiers(PrintWriter out, int modifiers) {
        if ((modifiers & Opcodes.ACC_PUBLIC) != 0)
            out.print("public ");

        if ((modifiers & Opcodes.ACC_PROTECTED) != 0)
            out.print("protected ");

        if ((modifiers & Opcodes.ACC_PRIVATE) != 0)
            out.print("private ");

        if ((modifiers & Opcodes.ACC_STATIC) != 0)
            out.print("static ");

        if ((modifiers & Opcodes.ACC_SYNCHRONIZED) != 0)
            out.print("synchronized ");

        if ((modifiers & Opcodes.ACC_ABSTRACT) != 0)
            out.print("abstract ");
    }

    private void genImports(ClassNode classNode, PrintWriter out) {
        List<String> imports = new ArrayList<String>();

        //
        // HACK: Add the default imports... since things like Closure and GroovyObject seem to parse out w/o fully qualified classnames.
        //
        // paulk: disabled below, doesn't seem to be needed any more (28 May 2010)
//        imports.addAll(Arrays.asList(ResolveVisitor.DEFAULT_IMPORTS));

        ModuleNode moduleNode = classNode.getModule();
        for (ImportNode importNode : moduleNode.getStarImports()) {
            imports.add(importNode.getPackageName());
        }

        for (ImportNode imp : moduleNode.getImports()) {
            imports.add(imp.getType().getName());
        }

        for (String imp : imports) {
            String s = new StringBuilder()
                .append("import ")
                .append(imp)
                .append((imp.charAt(imp.length() - 1) == '.') ? "*;" : ";")
                .toString()
                .replace('$', '.');
                
            out.println(s);
        }
        out.println();
    }

    public void clean() {
        for (String path : toCompile) {
            new File(outputPath, path + ".java").delete();
        }
    }
}
