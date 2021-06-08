/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.treeshaker;

import static javax.lang.model.element.Modifier.STATIC;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.j2objc.ast.Annotation;
import com.google.devtools.j2objc.ast.AnnotationTypeDeclaration;
import com.google.devtools.j2objc.ast.ClassInstanceCreation;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.ast.ConstructorInvocation;
import com.google.devtools.j2objc.ast.EnumConstantDeclaration;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.ExpressionMethodReference;
import com.google.devtools.j2objc.ast.FieldAccess;
import com.google.devtools.j2objc.ast.LambdaExpression;
import com.google.devtools.j2objc.ast.MarkerAnnotation;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.NormalAnnotation;
import com.google.devtools.j2objc.ast.PackageDeclaration;
import com.google.devtools.j2objc.ast.PropertyAnnotation;
import com.google.devtools.j2objc.ast.SingleMemberAnnotation;
import com.google.devtools.j2objc.ast.SuperConstructorInvocation;
import com.google.devtools.j2objc.ast.SuperMethodInvocation;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.ast.TypeMethodReference;
import com.google.devtools.j2objc.ast.UnitTreeVisitor;
import com.google.devtools.j2objc.ast.VariableDeclarationFragment;
import com.google.devtools.j2objc.util.CodeReferenceMap;
import com.google.devtools.j2objc.util.ElementUtil;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

final class UsedCodeMarker extends UnitTreeVisitor {
  static final String CLASS_INITIALIZER_NAME = "<clinit>##()V";
  static final String EMPTY_METHOD_SIGNATURE = "()V";
  static final String INTERFACE_SUPERTYPE = "none";
  static final String PSEUDO_CONSTRUCTOR_PREFIX = "%%";
  static final String SIGNATURE_PREFIX = "##";

  private final Context context;

  UsedCodeMarker(CompilationUnit unit, Context context) {
    super(unit);
    this.context = context;
  }

  @Override
  public boolean visit(AnnotationTypeDeclaration node) {
    startType(node.getTypeElement(), true);
    return true;
  }

  @Override
  public void endVisit(AnnotationTypeDeclaration node) {
    endType();
  }

  @Override
  public void endVisit(ClassInstanceCreation node) {
    addMethodInvocation(
        getMethodName(node.getExecutableElement()),
        getDeclaringClassName(node.getExecutableElement()));
    // Creating an instance of an anonymous class also creates instances of the interfaces.
    if (node.getAnonymousClassDeclaration() != null) {
      for (TypeMirror type : node.getAnonymousClassDeclaration().getSuperInterfaceTypeMirrors()) {
        addMethodInvocation(getPseudoConstructorName(type.toString()), type.toString());
      }
    }
  }

  @Override
  public void endVisit(ConstructorInvocation node) {
    addMethodInvocation(
        getMethodName(node.getExecutableElement()),
        getDeclaringClassName(node.getExecutableElement()));
  }

  @Override
  public void endVisit(EnumConstantDeclaration node) {
    addMethodInvocation(
        getMethodName(node.getExecutableElement()),
        getDeclaringClassName(node.getExecutableElement()));
  }

  @Override
  public boolean visit(EnumDeclaration node) {
    startType(node.getTypeElement(), false);
    return true;
  }

  @Override
  public void endVisit(EnumDeclaration node) {
    endType();
  }

  @Override
  public void endVisit(ExpressionMethodReference node) {
    addMethodInvocation(
        getMethodName(node.getExecutableElement()),
        getDeclaringClassName(node.getExecutableElement()));
  }

  @Override
  public void endVisit(FieldAccess node) {
    // Note: accessing a static field of a class implicitly runs the class' static initializer.
    if (node.getVariableElement().getModifiers().contains(STATIC)) {
      addMethodInvocation(
          CLASS_INITIALIZER_NAME,
          node.getExpression().getTypeMirror().toString());
    }
  }

  @Override
  public void endVisit(LambdaExpression node) {
    // A lambda expression implicitly constructs an instance of the interface that it implements.
    addMethodInvocation(
        getPseudoConstructorName(node.getTypeMirror().toString()),
        node.getTypeMirror().toString());
  }

  @Override
  public void endVisit(MarkerAnnotation node) {
    visitAnnotation(node);
  }

  @Override
  public boolean visit(MethodDeclaration node) {
    startMethodDeclaration(
        getMethodName(node.getExecutableElement()),
        getDeclaringClassName(node.getExecutableElement()),
        node.isConstructor(),
        Modifier.isStatic(node.getModifiers()));
    return true;
  }

  @Override
  public void endVisit(MethodDeclaration node) {
    endMethodDeclaration();
  }

  @Override
  public void endVisit(MethodInvocation node) {
    addMethodInvocation(
        getMethodName(node.getExecutableElement()),
        getDeclaringClassName(node.getExecutableElement()));
    addReferencedType(node.getExecutableType().getReturnType());
    node.getExecutableType().getParameterTypes().forEach(this::addReferencedType);
  }

  @Override
  public void endVisit(NormalAnnotation node) {
    visitAnnotation(node);
  }

  @Override
  public boolean visit(PackageDeclaration node) {
    if (!node.getAnnotations().isEmpty()) {
      // Package annotations are only allowed in package-info.java files.
      startPackage(node.getPackageElement());
      node.getAnnotations().forEach(this::visitAnnotation);
      endPackage();
    }
    return false;
  }

  @Override
  public void endVisit(PropertyAnnotation node) {
    visitAnnotation(node);
  }

  @Override
  public void endVisit(SingleMemberAnnotation node) {
    visitAnnotation(node);
  }

  @Override
  public void endVisit(SuperConstructorInvocation node) {
    addMethodInvocation(
        getMethodName(node.getExecutableElement()),
        getDeclaringClassName(node.getExecutableElement()));
  }

  @Override
  public void endVisit(SuperMethodInvocation node) {
    addMethodInvocation(
        getMethodName(node.getExecutableElement()),
        getDeclaringClassName(node.getExecutableElement()));
    addReferencedType(node.getExecutableType().getReturnType());
    node.getExecutableType().getParameterTypes().forEach(this::addReferencedType);
  }

  @Override
  public boolean visit(TypeDeclaration node) {
    startType(node.getTypeElement(), node.isInterface());
    return true;
  }

  @Override
  public void endVisit(TypeDeclaration node) {
    endType();
  }

  @Override
  public void endVisit(TypeMethodReference node) {
    addMethodInvocation(
        getMethodName(node.getExecutableElement()),
        getDeclaringClassName(node.getExecutableElement()));
    // A method expression implicitly constructs an instance of the interface that it implements.
    addMethodInvocation(
        getPseudoConstructorName(node.getTypeMirror().toString()),
        node.getTypeMirror().toString());
  }

  @Override
  public void endVisit(VariableDeclarationFragment node) {
    addReferencedType(node.getVariableElement().asType());
  }

  private String getDeclaringClassName(ExecutableElement method) {
    return elementUtil.getBinaryName(ElementUtil.getDeclaringClass(method));
  }

  @VisibleForTesting
  static String getQualifiedMethodName(String type, String name, String signature) {
    return getQualifiedMethodName(type, getMethodName(name, signature));
  }

  private static String getQualifiedMethodName(String type, String nameAndSignature) {
    return eraseParametricTypes(type) + "." + nameAndSignature;
  }

  private static String getMethodName(String name, String signature) {
    return name + SIGNATURE_PREFIX + signature;
  }

  private String getMethodName(ExecutableElement method) {
    return getMethodName(typeUtil.getReferenceName(method), typeUtil.getReferenceSignature(method));
  }

  private static String getPseudoConstructorName(String type) {
    type = eraseParametricTypes(type);
    return getMethodName(
        PSEUDO_CONSTRUCTOR_PREFIX + type.substring(type.lastIndexOf('.') + 1),
        EMPTY_METHOD_SIGNATURE);
  }

  private static boolean isUntrackedClass(String typeName) {
    return typeName.indexOf('.') == -1;
  }

  private void visitAnnotation(Annotation node) {
    // A reference to an annotation implicitly constructs an instance of that annotation.
    addMethodInvocation(
        getPseudoConstructorName(node.getTypeMirror().toString()),
        node.getTypeMirror().toString());
  }

  @VisibleForTesting
  static String eraseParametricTypes(String typeName) {
    // cases:
    // - no paramatric types: C -> C
    // - simple parametric type: C<A> -> C
    // - nested parametric type: C<D<A>> -> C
    // - nested multi-parametric type: C<D<A>,D<B>> -> C
    // - chained parametric type: C<A>.D<A> -> C.D
    int begin = typeName.indexOf('<');
    if (begin == -1) {
      return typeName;
    }
    int unmatched = 1;
    int index = begin + 1;
    while (unmatched > 0 && index < typeName.length()) {
      char current = typeName.charAt(index);
      if (current == '<') {
        unmatched++;
      } else if (current == '>') {
        unmatched--;
      }
      index++;
    }
    String first = typeName.substring(0, begin);
    if (index == typeName.length()) {
      return first;
    }
    return first + eraseParametricTypes(typeName.substring(index));
  }

  private Integer getTypeId(String typeName) {
    String rawTypeName = eraseParametricTypes(typeName);
    Integer index = context.typeMap.putIfAbsent(rawTypeName, context.typeCount);
    if (index == null) {
      context.libraryInfoBuilder.addTypeMap(rawTypeName);
      return context.typeCount++;
    }
    return index;
  }

  private void startPackage(PackageElement pkg) {
    String pkgName =  pkg.getQualifiedName() + ".package-info";
    startTypeScope(pkgName, INTERFACE_SUPERTYPE, false, ImmutableList.of(), true);
  }

  private void endPackage() {
    endTypeScope();
  }

  private void startType(TypeElement type, boolean isInterface) {
    String typeName = elementUtil.getBinaryName(type);
    TypeElement superType = ElementUtil.getSuperclass(type);
    String superName =
        superType == null ? INTERFACE_SUPERTYPE : elementUtil.getBinaryName(superType);
    List<String> interfaces = ElementUtil.getInterfaces(type).stream()
                                .map(elementUtil::getBinaryName).collect(Collectors.toList());
    boolean isExported = context.exportedClasses.contains(typeName);
    startTypeScope(typeName, superName, isInterface, interfaces, isExported);
  }

  private void endType() {
    endTypeScope();
  }

  private void startTypeScope(String typeName, String superName, boolean isInterface,
      List<String> interfaces, boolean isExported) {
    if (isUntrackedClass(typeName)) {
      // Methods of anonymous and local classes are not tracked.
      if (context.currentTypeNameScope.isEmpty()) {
        // Treat top-level classes w/o a package name as an exported class (i.e. don't remove
        // the class).
        isExported = true;
      } else {
        context.currentTypeCloserScope.push(Context.nopCloser);
        return;
      }
    }

    Integer id = getTypeId(typeName);
    Integer eid = getTypeId(superName);
    List<Integer> iids = interfaces.stream().map(this::getTypeId).collect(Collectors.toList());
    context.currentTypeNameScope.push(typeName);
    context.currentTypeInfoScope.push(TypeInfo.newBuilder()
        .setTypeId(id).setExtendsType(eid).addAllImplementsType(iids).setExported(isExported));
    context.currentTypeCloserScope.push(this::closeTypeScope);
    // Push the static initializer as the current method in scope.
    startMethodScope(CLASS_INITIALIZER_NAME, MemberInfo.newBuilder()
        .setName(CLASS_INITIALIZER_NAME).setStatic(true).setExported(isExported));
    // For interfaces, add a pseudo-constructor for use with lambdas.
    if (isInterface) {
      startMethodDeclaration(getPseudoConstructorName(typeName), typeName, true, false);
      endMethodDeclaration();
    }
  }

  private void endTypeScope() {
    context.currentTypeCloserScope.pop().close();
  }

  private void closeTypeScope() {
    // Close the current method (i.e. the static initializer).
    closeMethodScope();
    TypeInfo ti = context.currentTypeInfoScope.pop().build();
    context.currentTypeNameScope.pop();
    // Add the type info to the library info.
    context.libraryInfoBuilder.addType(ti);
  }

  private void startMethodScope(String methodName, MemberInfo.Builder mib) {
    context.mibScope.push(mib);
    context.methodNameScope.push(methodName);
    context.referencedTypesScope.push(new HashSet<>());
  }

  private void closeMethodScope() {
    for (Integer typeId : context.referencedTypesScope.pop()) {
      context.mibScope.peek().addReferencedTypes(typeId);
    }
    context.methodNameScope.pop();
    MemberInfo mi = context.mibScope.pop().build();
    context.currentTypeInfoScope.peek().addMember(mi);
  }

  private void startMethodDeclaration(
      String methodName, String declTypeName, boolean isConstructor, boolean isStatic) {
    if (isUntrackedClass(declTypeName)) {
      // Methods of anonymous and local classes are not tracked.
      context.currentMethodCloserScope.push(Context.nopCloser);
      return;
    }
    boolean isExported =
        context.exportedMethods.contains(
            getQualifiedMethodName(context.currentTypeNameScope.peek(), methodName))
        || context.currentTypeInfoScope.peek().getExported();
    context.currentMethodCloserScope.push(this::closeMethodScope);
    startMethodScope(methodName,
        MemberInfo.newBuilder()
        .setName(methodName)
        .setStatic(isStatic)
        .setConstructor(isConstructor)
        .setExported(isExported));
  }

  private void addMethodInvocation(String methodName, String declTypeName) {
    if (isUntrackedClass(declTypeName)) {
      // Methods of anonymous and local classes are not tracked.
      return;
    }
    int declTypeId = getTypeId(declTypeName);
    context.mibScope.peek()
            .addInvokedMethods(com.google.devtools.treeshaker.MethodInvocation.newBuilder()
                .setMethod(methodName)
                .setEnclosingType(declTypeId)
                .build());
    addReferencedTypeName(declTypeName);
  }

  private void addReferencedType(TypeMirror type) {
    boolean isPrimitive = type.getKind().isPrimitive();
    if (isPrimitive)  {
      return;
    }
    addReferencedTypeName(type.toString());
  }

  private void addReferencedTypeName(String typeName) {
    int typeId = getTypeId(typeName);
    context.referencedTypesScope.peek().add(typeId);
  }

  private void endMethodDeclaration() {
    context.currentMethodCloserScope.pop().close();
  }

  static final class Context {
    private interface Closer { void close(); }

    private static final Closer nopCloser = () -> {};

    // Map of type names to unique integer.
    private int typeCount;
    private final Map<String, Integer> typeMap = new HashMap<>();

    // Qualified method names that are exported (live).
    private final Set<String> exportedMethods;

    // Qualified class names that are exported (live).
    private final ImmutableSet<String> exportedClasses;

    // Library info builder, which contains all of the types processed.
    private final LibraryInfo.Builder libraryInfoBuilder = LibraryInfo.newBuilder();

    // Scope containing data for the current type being processed.
    private final Deque<String> currentTypeNameScope = new ArrayDeque<>();
    private final Deque<TypeInfo.Builder> currentTypeInfoScope = new ArrayDeque<>();
    private final Deque<Closer> currentTypeCloserScope = new ArrayDeque<>();

    // Scope containing data for the current method being processed.
    private final Deque<MemberInfo.Builder> mibScope = new ArrayDeque<>();
    private final Deque<String> methodNameScope = new ArrayDeque<>();
    private final Deque<Set<Integer>> referencedTypesScope = new ArrayDeque<>();
    private final Deque<Closer> currentMethodCloserScope = new ArrayDeque<>();

    Context(CodeReferenceMap rootSet) {
      exportedMethods = new HashSet<>();
      rootSet.getReferencedMethods().cellSet().forEach(cell -> {
        String type  = cell.getRowKey();
        String name = cell.getColumnKey();
        cell.getValue().forEach(signature ->
            exportedMethods.add(getQualifiedMethodName(type, name, signature)));
      });
      exportedClasses = rootSet.getReferencedClasses();
    }

    LibraryInfo getLibraryInfo() {
      return libraryInfoBuilder.build();
    }
  }
}
