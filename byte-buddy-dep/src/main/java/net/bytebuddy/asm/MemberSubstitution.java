package net.bytebuddy.asm;

import lombok.EqualsAndHashCode;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.Duplication;
import net.bytebuddy.implementation.bytecode.Removal;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.constant.DefaultValue;
import net.bytebuddy.implementation.bytecode.member.FieldAccess;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.CompoundList;
import net.bytebuddy.utility.visitor.LocalVariableAwareMethodVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * <p>
 * Substitutes field access or method invocations within a method's body.
 * </p>
 * <p>
 * <b>Important</b>: This component relies on using a {@link TypePool} for locating types within method bodies. Within a redefinition
 * or a rebasement, this type pool normally resolved correctly by Byte Buddy. When subclassing a type, the type pool must be set
 * explicitly, using {@link net.bytebuddy.dynamic.DynamicType.Builder#make(TypePool)} or any similar method. It is however not normally
 * necessary to use this component when subclassing a type where methods are only defined explicitly.
 * </p>
 */
@EqualsAndHashCode(callSuper = false)
public class MemberSubstitution implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {

    // TODO: Include configuration for using different TypePool!

    /**
     * The method graph compiler to use.
     */
    private final MethodGraph.Compiler methodGraphCompiler;

    /**
     * {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
     */
    private final boolean strict;

    /**
     * The substitution to apply.
     */
    private final Substitution substitution;

    /**
     * Creates a new member substitutor.
     *
     * @param methodGraphCompiler The method graph compiler to use.
     * @param strict              {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
     * @param substitution        The substitution to apply.
     */
    protected MemberSubstitution(MethodGraph.Compiler methodGraphCompiler, boolean strict, Substitution substitution) {
        this.methodGraphCompiler = methodGraphCompiler;
        this.strict = strict;
        this.substitution = substitution;
    }

    /**
     * Creates a member substitutor that requires the resolution of all fields and methods that are referenced within a method body. Doing so,
     * this component raises an exception if any member cannot be resolved what makes this component unusable when facing optional types.
     *
     * @return A strict member substitutor.
     */
    public static MemberSubstitution strict() {
        return new MemberSubstitution(MethodGraph.Compiler.DEFAULT, true, Substitution.NoOp.INSTANCE);
    }

    /**
     * Creates a member substitutor that skips any unresolvable fields or methods that are referenced within a method body. Using a relaxed
     * member substitutor, methods containing optional types are supported. In the process, it is however possible that misconfigurations
     * of this component remain undiscovered.
     *
     * @return A relaxed member substitutor.
     */
    public static MemberSubstitution relaxed() {
        return new MemberSubstitution(MethodGraph.Compiler.DEFAULT, false, Substitution.NoOp.INSTANCE);
    }

    /**
     * Substitutes any interaction with a field or method that matches the given matcher.
     *
     * @param matcher The matcher to determine what access to byte code elements to substitute.
     * @return A specification that allows to determine how to substitute any interaction with byte code elements that match the supplied matcher.
     */
    public WithoutSpecification element(ElementMatcher<? super ByteCodeElement> matcher) {
        return new WithoutSpecification.ForMatchedByteCodeElement(methodGraphCompiler, strict, substitution, matcher);
    }

    /**
     * Substitutes any field access that matches the given matcher.
     *
     * @param matcher The matcher to determine what fields to substitute.
     * @return A specification that allows to determine how to substitute any field access that match the supplied matcher.
     */
    public WithoutSpecification.ForMatchedField field(ElementMatcher<? super FieldDescription.InDefinedShape> matcher) {
        return new WithoutSpecification.ForMatchedField(methodGraphCompiler, strict, substitution, matcher);
    }

    /**
     * Substitutes any method invocation that matches the given matcher.
     *
     * @param matcher The matcher to determine what methods to substitute.
     * @return A specification that allows to determine how to substitute any method invocations that match the supplied matcher.
     */
    public WithoutSpecification.ForMatchedMethod method(ElementMatcher<? super MethodDescription> matcher) {
        return new WithoutSpecification.ForMatchedMethod(methodGraphCompiler, strict, substitution, matcher);
    }

    /**
     * Substitutes any constructor invocation that matches the given matcher.
     *
     * @param matcher The matcher to determine what constructors to substitute.
     * @return A specification that allows to determine how to substitute any constructor invocations that match the supplied matcher.
     */
    public WithoutSpecification constructor(ElementMatcher<? super MethodDescription> matcher) {
        return invokable(isConstructor().and(matcher));
    }

    /**
     * Substitutes any method or constructor invocation that matches the given matcher.
     *
     * @param matcher The matcher to determine what method or constructors to substitute.
     * @return A specification that allows to determine how to substitute any constructor invocations that match the supplied matcher.
     */
    public WithoutSpecification invokable(ElementMatcher<? super MethodDescription> matcher) {
        return new WithoutSpecification.ForMatchedMethod(methodGraphCompiler, strict, substitution, matcher);
    }

    /**
     * Specifies the use of a specific method graph compiler for the resolution of virtual methods.
     *
     * @param methodGraphCompiler The method graph compiler to use.
     * @return A new member substitution that is equal to this but uses the specified method graph compiler.
     */
    public MemberSubstitution with(MethodGraph.Compiler methodGraphCompiler) {
        return new MemberSubstitution(methodGraphCompiler, strict, substitution);
    }

    /**
     * Applies this member substitutor to any method that matches the supplied matcher.
     *
     * @param matcher The matcher to determine this substitutors application.
     * @return An ASM visitor wrapper that applies all specified substitutions for any matched method.
     */
    public AsmVisitorWrapper.ForDeclaredMethods on(ElementMatcher<? super MethodDescription> matcher) {
        return new AsmVisitorWrapper.ForDeclaredMethods().method(matcher, this);
    }

    @Override
    public MethodVisitor wrap(TypeDescription instrumentedType,
                              MethodDescription instrumentedMethod,
                              MethodVisitor methodVisitor,
                              Implementation.Context implementationContext,
                              TypePool typePool,
                              int writerFlags,
                              int readerFlags) {
        return new SubstitutingMethodVisitor(methodVisitor,
                methodGraphCompiler,
                strict,
                substitution,
                instrumentedType,
                instrumentedMethod, implementationContext,
                typePool);
    }

    /**
     * A member substitution that lacks a specification for how to substitute the matched members references within a method body.
     */
    @EqualsAndHashCode
    public abstract static class WithoutSpecification {

        /**
         * The method graph compiler to use.
         */
        protected final MethodGraph.Compiler methodGraphCompiler;

        /**
         * {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
         */
        protected final boolean strict;

        /**
         * The substitution to apply.
         */
        protected final Substitution substitution;

        /**
         * Creates a new member substitution that requires a specification for how to perform a substitution.
         *
         * @param methodGraphCompiler The method graph compiler to use.
         * @param strict              {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
         * @param substitution        The substitution to apply.
         */
        protected WithoutSpecification(MethodGraph.Compiler methodGraphCompiler,
                                       boolean strict,
                                       Substitution substitution) {
            this.methodGraphCompiler = methodGraphCompiler;
            this.strict = strict;
            this.substitution = substitution;
        }

        /**
         * Subs any interaction with a matched byte code element. Any value read from the element will be replaced with the stubbed
         * value's default, i.e. {@code null} for reference types and the specific {@code 0} value for primitive types. Any writen
         * value will simply be discarded.
         *
         * @return A member substitution that stubs any interaction with a matched byte code element.
         */
        public MemberSubstitution stub() {
            return new MemberSubstitution(methodGraphCompiler,
                    strict,
                    new Substitution.Compound(doStub(), substitution));
        }

        /**
         * Applies the stubbing for this instance.
         *
         * @return A suitable substitution.
         */
        protected abstract Substitution doStub();

        /**
         * <p>
         * Replaces any interaction with a matched byte code element by an interaction with the specified field. If a field
         * is replacing a method or constructor invocation, it is treated as if it was a field getter or setter respectively.
         * </p>
         * <p>
         * A replacement can only be applied if the field is compatible to the original byte code element, i.e. consumes an
         * instance of the declaring type if it is not {@code static} as an argument and consumes or produces an instance of
         * the field's type.
         * </p>
         *
         * @param field The field to access instead of interacting with any of the matched byte code elements.
         * @return A member substitution that replaces any matched byte code element with an access of the specified field.
         */
        public MemberSubstitution replaceWith(Field field) {
            return replaceWith(new FieldDescription.ForLoadedField(field));
        }

        /**
         * <p>
         * Replaces any interaction with a matched byte code element by an interaction with the specified field. If a field
         * is replacing a method or constructor invocation, it is treated as if it was a field getter or setter respectively.
         * </p>
         * <p>
         * A replacement can only be applied if the field is compatible to the original byte code element, i.e. consumes an
         * instance of the declaring type if it is not {@code static} as an argument and consumes or produces an instance of
         * the field's type.
         * </p>
         *
         * @param fieldDescription The field to access instead of interacting with any of the matched byte code elements.
         * @return A member substitution that replaces any matched byte code element with an access of the specified field.
         */
        public MemberSubstitution replaceWith(FieldDescription fieldDescription) {
            return new MemberSubstitution(methodGraphCompiler,
                    strict,
                    new Substitution.Compound(doReplaceWith(fieldDescription), substitution));
        }

        /**
         * Creates a substitution for replacing the byte code elements matched by this instance with an access of the specified field.
         *
         * @param fieldDescription The field to access.
         * @return A suitable substitution.
         */
        protected abstract Substitution doReplaceWith(FieldDescription fieldDescription);

        /**
         * <p>
         * Replaces any interaction with a matched byte code element by an invocation of the specified method. If a method
         * is replacing a field access, it is treated as if it was replacing an invocation of the field's getter or setter respectively.
         * </p>
         * <p>
         * A replacement can only be applied if the method is compatible to the original byte code element, i.e. consumes compatible
         * arguments and returns a compatible value. If the method is not {@code static}, it is treated as if {@code this} was an implcit
         * first argument.
         * </p>
         *
         * @param method The method to invoke instead of interacting with any of the matched byte code elements.
         * @return A member substitution that replaces any matched byte code element with an invocation of the specified method.
         */
        public MemberSubstitution replaceWith(Method method) {
            return replaceWith(new MethodDescription.ForLoadedMethod(method));
        }

        /**
         * <p>
         * Replaces any interaction with a matched byte code element by an invocation of the specified method. If a method
         * is replacing a field access, it is treated as if it was replacing an invocation of the field's getter or setter respectively.
         * </p>
         * <p>
         * A replacement can only be applied if the method is compatible to the original byte code element, i.e. consumes compatible
         * arguments and returns a compatible value. If the method is not {@code static}, it is treated as if {@code this} was an implcit
         * first argument.
         * </p>
         * <p>
         * <b>Important</b>: It is not allowed to specifiy a constructor or the static type initializer as a replacement.
         * </p>
         *
         * @param methodDescription The method to invoke instead of interacting with any of the matched byte code elements.
         * @return A member substitution that replaces any matched byte code element with an invocation of the specified method.
         */
        public MemberSubstitution replaceWith(MethodDescription methodDescription) {
            if (!methodDescription.isMethod()) {
                throw new IllegalArgumentException("Cannot use " + methodDescription + " as a replacement");
            }
            return new MemberSubstitution(methodGraphCompiler,
                    strict,
                    new Substitution.Compound(doReplaceWith(methodDescription), substitution));
        }

        /**
         * Creates a substitution for replacing the byte code elements matched by this instance with an invocation of the specified method.
         *
         * @param methodDescription The method to invoke.
         * @return A suitable substitution.
         */
        protected abstract Substitution doReplaceWith(MethodDescription methodDescription);

        /**
         * Describes a member substitution that requires a specification for how to replace a byte code element.
         */
        @EqualsAndHashCode(callSuper = true)
        protected static class ForMatchedByteCodeElement extends WithoutSpecification {

            /**
             * A matcher for any byte code elements that should be substituted.
             */
            private final ElementMatcher<? super ByteCodeElement> matcher;

            /**
             * Creates a new member substitution for a matched byte code element that requires a specification for how to perform a substitution.
             *
             * @param methodGraphCompiler The method graph compiler to use.
             * @param strict              {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
             * @param substitution        The substitution to apply.
             * @param matcher             A matcher for any byte code elements that should be substituted.
             */
            protected ForMatchedByteCodeElement(MethodGraph.Compiler methodGraphCompiler,
                                                boolean strict,
                                                Substitution substitution,
                                                ElementMatcher<? super ByteCodeElement> matcher) {
                super(methodGraphCompiler, strict, substitution);
                this.matcher = matcher;
            }

            @Override
            protected Substitution doStub() {
                return Substitution.ForElementMatchers.of(matcher, Substitution.Resolver.Stubbing.INSTANCE);
            }

            @Override
            protected Substitution doReplaceWith(FieldDescription fieldDescription) {
                return Substitution.ForElementMatchers.of(matcher, new Substitution.Resolver.FieldAccessing(fieldDescription));
            }

            @Override
            protected Substitution doReplaceWith(MethodDescription methodDescription) {
                return Substitution.ForElementMatchers.of(matcher, new Substitution.Resolver.MethodInvoking(methodDescription));
            }
        }

        /**
         * Describes a member substitution that requires a specification for how to replace a field.
         */
        @EqualsAndHashCode(callSuper = true)
        public static class ForMatchedField extends WithoutSpecification {

            /**
             * A matcher for any field that should be substituted.
             */
            private final ElementMatcher<? super FieldDescription.InDefinedShape> matcher;

            /**
             * {@code true} if read access to a field should be substituted.
             */
            private final boolean matchRead;

            /**
             * {@code true} if write access to a field should be substituted.
             */
            private final boolean matchWrite;

            /**
             * Creates a new member substitution for a matched field that requires a specification for how to perform a substitution.
             *
             * @param methodGraphCompiler The method graph compiler to use.
             * @param strict              {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
             * @param substitution        The substitution to apply.
             * @param matcher             A matcher for any field that should be substituted.
             */
            protected ForMatchedField(MethodGraph.Compiler methodGraphCompiler,
                                      boolean strict,
                                      Substitution substitution,
                                      ElementMatcher<? super FieldDescription.InDefinedShape> matcher) {
                this(methodGraphCompiler, strict, substitution, matcher, true, true);
            }

            /**
             * Creates a new member substitution for a matched field that requires a specification for how to perform a substitution.
             *
             * @param methodGraphCompiler The method graph compiler to use.
             * @param strict              {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
             * @param substitution        The substitution to apply.
             * @param matcher             A matcher for any field that should be substituted.
             * @param matchRead           {@code true} if read access to a field should be substituted.
             * @param matchWrite          {@code true} if write access to a field should be substituted.
             */
            protected ForMatchedField(MethodGraph.Compiler methodGraphCompiler,
                                      boolean strict,
                                      Substitution substitution,
                                      ElementMatcher<? super FieldDescription.InDefinedShape> matcher,
                                      boolean matchRead,
                                      boolean matchWrite) {
                super(methodGraphCompiler, strict, substitution);
                this.matcher = matcher;
                this.matchRead = matchRead;
                this.matchWrite = matchWrite;
            }

            /**
             * When invoked, only read access of the previously matched field is substituted.
             *
             * @return This instance with the limitation that only read access to the matched field is substituted.
             */
            public WithoutSpecification onRead() {
                return new ForMatchedField(methodGraphCompiler, strict, substitution, matcher, true, false);
            }

            /**
             * When invoked, only write access of the previously matched field is substituted.
             *
             * @return This instance with the limitation that only write access to the matched field is substituted.
             */
            public WithoutSpecification onWrite() {
                return new ForMatchedField(methodGraphCompiler, strict, substitution, matcher, false, true);
            }

            @Override
            protected Substitution doStub() {
                return Substitution.ForElementMatchers.ofField(matcher, matchRead, matchWrite, Substitution.Resolver.Stubbing.INSTANCE);
            }

            @Override
            protected Substitution doReplaceWith(FieldDescription fieldDescription) {
                return Substitution.ForElementMatchers.ofField(matcher, matchRead, matchWrite, new Substitution.Resolver.FieldAccessing(fieldDescription));
            }

            @Override
            protected Substitution doReplaceWith(MethodDescription methodDescription) {
                return Substitution.ForElementMatchers.ofField(matcher, matchRead, matchWrite, new Substitution.Resolver.MethodInvoking(methodDescription));
            }
        }

        /**
         * Describes a member substitution that requires a specification for how to replace a method or constructor.
         */
        @EqualsAndHashCode(callSuper = true)
        public static class ForMatchedMethod extends WithoutSpecification {

            /**
             * A matcher for any method or constructor that should be substituted.
             */
            private final ElementMatcher<? super MethodDescription> matcher;

            /**
             * {@code true} if this specification includes virtual invocations.
             */
            private final boolean includeVirtualCalls;

            /**
             * {@code true} if this specification includes {@code super} invocations.
             */
            private final boolean includeSuperCalls;

            /**
             * Creates a new member substitution for a matched method that requires a specification for how to perform a substitution.
             *
             * @param methodGraphCompiler The method graph compiler to use.
             * @param strict              {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
             * @param substitution        The substitution to apply.
             * @param matcher             A matcher for any method or constructor that should be substituted.
             */
            protected ForMatchedMethod(MethodGraph.Compiler methodGraphCompiler,
                                       boolean strict,
                                       Substitution substitution,
                                       ElementMatcher<? super MethodDescription> matcher) {
                this(methodGraphCompiler, strict, substitution, matcher, true, true);
            }

            /**
             * Creates a new member substitution for a matched method that requires a specification for how to perform a substitution.
             *
             * @param methodGraphCompiler The method graph compiler to use.
             * @param strict              {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
             * @param substitution        The substitution to apply.
             * @param matcher             A matcher for any method or constructor that should be substituted.
             * @param includeVirtualCalls {@code true} if this specification includes virtual invocations.
             * @param includeSuperCalls   {@code true} if this specification includes {@code super} invocations.
             */
            protected ForMatchedMethod(MethodGraph.Compiler methodGraphCompiler,
                                       boolean strict,
                                       Substitution substitution,
                                       ElementMatcher<? super MethodDescription> matcher,
                                       boolean includeVirtualCalls,
                                       boolean includeSuperCalls) {
                super(methodGraphCompiler, strict, substitution);
                this.matcher = matcher;
                this.includeVirtualCalls = includeVirtualCalls;
                this.includeSuperCalls = includeSuperCalls;
            }

            /**
             * Limits the substituted method calls to method calls that invoke a method virtually (as opposed to a {@code super} invocation).
             *
             * @return This specification where only virtual methods are matched if they are not invoked as a virtual call.
             */
            public WithoutSpecification onVirtualCall() {
                return new ForMatchedMethod(methodGraphCompiler, strict, substitution, isVirtual().and(matcher), true, false);
            }

            /**
             * Limits the substituted method calls to method calls that invoke a method as a {@code super} call.
             *
             * @return This specification where only virtual methods are matched if they are not invoked as a super call.
             */
            public WithoutSpecification onSuperCall() {
                return new ForMatchedMethod(methodGraphCompiler, strict, substitution, isVirtual().and(matcher), false, true);
            }

            @Override
            protected Substitution doStub() {
                return Substitution.ForElementMatchers.ofMethod(matcher, includeVirtualCalls, includeSuperCalls, Substitution.Resolver.Stubbing.INSTANCE);
            }

            @Override
            protected Substitution doReplaceWith(FieldDescription fieldDescription) {
                return Substitution.ForElementMatchers.ofMethod(matcher,
                        includeVirtualCalls,
                        includeSuperCalls,
                        new Substitution.Resolver.FieldAccessing(fieldDescription));
            }

            @Override
            protected Substitution doReplaceWith(MethodDescription methodDescription) {
                return Substitution.ForElementMatchers.ofMethod(matcher,
                        includeVirtualCalls,
                        includeSuperCalls,
                        new Substitution.Resolver.MethodInvoking(methodDescription));
            }
        }
    }

    /**
     * Resolves an actual substitution.
     */
    protected interface Substitution {

        /**
         * Resolves a field access within a method body.
         *
         * @param fieldDescription The field being accessed.
         * @param writeAccess      {@code true} if the access is for writing to the field, {@code false} if the field is read.
         * @return A resolver for the supplied field access.
         */
        Resolver resolve(FieldDescription.InDefinedShape fieldDescription, boolean writeAccess);

        /**
         * Resolves a method invocation within a method body.
         *
         * @param methodDescription The method being invoked.
         * @param invocationType    The method's invocation type.
         * @return A resolver for the supplied method invocation.
         */
        Resolver resolve(MethodDescription methodDescription, InvocationType invocationType);

        /**
         * A resolver supplies an implementation for a substitution.
         */
        interface Resolver {

            /**
             * Checks if this resolver was actually resolved, i.e. if a member should be substituted at all.
             *
             * @return {@code true} if a found member should be substituted.
             */
            boolean isResolved();

            /**
             * Applies this resolver. This is only legal for resolved resolvers.
             *
             * @param instrumentedType The instrumented type.
             * @param target           The substituted byte code element.
             * @param arguments        The factual arguments to the byte code element.
             * @param result           The expected result type or {@code void} if no result is expected.
             * @return A stack manipulation that applies the resolved byte code representing the substitution.
             */
            Binding apply(TypeDescription instrumentedType,
                          MethodDescription instrumentedMethod,
                          ByteCodeElement target,
                          TypeList.Generic arguments,
                          TypeDescription.Generic result,
                          int offset);

            class Binding {

                private final StackManipulation stackManipulation;

                private final int offset;

                protected Binding(StackManipulation stackManipulation, int offset) {
                    this.stackManipulation = stackManipulation;
                    this.offset = offset;
                }

                protected StackManipulation getStackManipulation() {
                    return stackManipulation;
                }

                protected int getOffset() {
                    return offset;
                }
            }

            /**
             * An unresolved resolver that does not apply a substitution.
             */
            enum Unresolved implements Resolver {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public boolean isResolved() {
                    return false;
                }

                @Override
                public Binding apply(TypeDescription instrumentedType,
                                     MethodDescription instrumentedMethod,
                                     ByteCodeElement target,
                                     TypeList.Generic arguments,
                                     TypeDescription.Generic result,
                                     int offset) {
                    throw new IllegalStateException("Cannot apply unresolved resolver");
                }
            }

            /**
             * A resolver that stubs any interaction with a byte code element.
             */
            enum Stubbing implements Resolver {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public boolean isResolved() {
                    return true;
                }

                @Override
                public Binding apply(TypeDescription instrumentedType,
                                     MethodDescription instrumentedMethod,
                                     ByteCodeElement target,
                                     TypeList.Generic arguments,
                                     TypeDescription.Generic result,
                                     int offset) {
                    List<StackManipulation> stackManipulations = new ArrayList<StackManipulation>(arguments.size());
                    for (int index = arguments.size() - 1; index >= 0; index--) {
                        stackManipulations.add(Removal.of(arguments.get(index)));
                    }
                    return new Binding(new StackManipulation.Compound(CompoundList.of(stackManipulations, DefaultValue.of(result))), offset);
                }
            }

            /**
             * A resolver that replaces an interaction with a byte code element with a field access.
             */
            @EqualsAndHashCode
            class FieldAccessing implements Resolver {

                /**
                 * The field that is used for substitution.
                 */
                private final FieldDescription fieldDescription;

                /**
                 * Creates a resolver for a field access.
                 *
                 * @param fieldDescription The field that is used for substitution.
                 */
                protected FieldAccessing(FieldDescription fieldDescription) {
                    this.fieldDescription = fieldDescription;
                }

                @Override
                public boolean isResolved() {
                    return true;
                }

                @Override
                public Binding apply(TypeDescription instrumentedType,
                                     MethodDescription instrumentedMethod,
                                     ByteCodeElement target,
                                     TypeList.Generic arguments,
                                     TypeDescription.Generic result,
                                     int offset) {
                    if (!fieldDescription.isAccessibleTo(instrumentedType)) {
                        throw new IllegalStateException(instrumentedType + " cannot access " + fieldDescription);
                    } else if (result.represents(void.class)) {
                        if (arguments.size() != (fieldDescription.isStatic() ? 1 : 2)) {
                            throw new IllegalStateException("Cannot set " + fieldDescription + " with " + arguments);
                        } else if (!fieldDescription.isStatic() && !arguments.get(0).asErasure().isAssignableTo(fieldDescription.getDeclaringType().asErasure())) {
                            throw new IllegalStateException("Cannot set " + fieldDescription + " on " + arguments.get(0));
                        } else if (!arguments.get(fieldDescription.isStatic() ? 0 : 1).asErasure().isAssignableTo(fieldDescription.getType().asErasure())) {
                            throw new IllegalStateException("Cannot set " + fieldDescription + " to " + arguments.get(fieldDescription.isStatic() ? 0 : 1));
                        }
                        return new Binding(FieldAccess.forField(fieldDescription).write(), offset);
                    } else {
                        if (arguments.size() != (fieldDescription.isStatic() ? 0 : 1)) {
                            throw new IllegalStateException("Cannot set " + fieldDescription + " with " + arguments);
                        } else if (!fieldDescription.isStatic() && !arguments.get(0).asErasure().isAssignableTo(fieldDescription.getDeclaringType().asErasure())) {
                            throw new IllegalStateException("Cannot get " + fieldDescription + " on " + arguments.get(0));
                        } else if (!fieldDescription.getType().asErasure().isAssignableTo(result.asErasure())) {
                            throw new IllegalStateException("Cannot get " + fieldDescription + " as " + result);
                        }
                        return new Binding(FieldAccess.forField(fieldDescription).read(), offset);
                    }
                }
            }

            /**
             * A resolver that invokes a method.
             */
            @EqualsAndHashCode
            class MethodInvoking implements Resolver {

                /**
                 * The method that is used for substitution.
                 */
                private final MethodDescription methodDescription;

                /**
                 * Creates a resolver for a method invocation.
                 *
                 * @param methodDescription The method that is used for substitution.
                 */
                protected MethodInvoking(MethodDescription methodDescription) {
                    this.methodDescription = methodDescription;
                }

                @Override
                public boolean isResolved() {
                    return true;
                }

                @Override
                public Binding apply(TypeDescription instrumentedType,
                                     MethodDescription instrumentedMethod,
                                     ByteCodeElement target,
                                     TypeList.Generic arguments,
                                     TypeDescription.Generic result,
                                     int offset) {
                    if (!methodDescription.isAccessibleTo(instrumentedType)) {
                        throw new IllegalStateException(instrumentedType + " cannot access " + methodDescription);
                    }
                    TypeList.Generic mapped = methodDescription.isStatic()
                            ? methodDescription.getParameters().asTypeList()
                            : new TypeList.Generic.Explicit(CompoundList.of(methodDescription.getDeclaringType(), methodDescription.getParameters().asTypeList()));
                    if (!methodDescription.getReturnType().asErasure().isAssignableTo(result.asErasure())) {
                        throw new IllegalStateException("Cannot assign return value of " + methodDescription + " to " + result);
                    } else if (mapped.size() != arguments.size()) {
                        throw new IllegalStateException("Cannot invoke " + methodDescription + " on " + arguments);
                    }
                    for (int index = 0; index < mapped.size(); index++) {
                        if (!mapped.get(index).asErasure().isAssignableTo(arguments.get(index).asErasure())) {
                            throw new IllegalStateException("Cannot invoke " + methodDescription + " on " + arguments);
                        }
                    }
                    return new Binding(methodDescription.isVirtual()
                            ? MethodInvocation.invoke(methodDescription).virtual(target.getDeclaringType().asErasure())
                            : MethodInvocation.invoke(methodDescription), offset);
                }
            }

            class Chaining implements Resolver {

                private final Assigner assigner;

                private final List<Element> elements;

                public Chaining(Assigner assigner, List<Element> elements) {
                    this.assigner = assigner;
                    this.elements = elements;
                }

                @Override
                public boolean isResolved() {
                    return true;
                }

                @Override
                public Binding apply(TypeDescription instrumentedType,
                                     MethodDescription instrumentedMethod,
                                     ByteCodeElement target,
                                     TypeList.Generic arguments,
                                     TypeDescription.Generic result,
                                     int offset) {
                    List<StackManipulation> stackManipulations = new ArrayList<StackManipulation>(arguments.size() + elements.size() + 3);
                    Map<Integer, Integer> argumentOffsets = new HashMap<Integer, Integer>();
                    for (int index = arguments.size() - 1; index >= 0; index--) {
                        argumentOffsets.put(index, offset);
                        stackManipulations.add(MethodVariableAccess.of(arguments.get(index)).storeAt(offset));
                        offset += arguments.get(index).getStackSize().getSize();
                    }
                    stackManipulations.add(DefaultValue.of(result));
                    stackManipulations.add(result.represents(void.class)
                            ? StackManipulation.Trivial.INSTANCE
                            : MethodVariableAccess.of(result).storeAt(offset));
                    for (Element element : elements) {
                        stackManipulations.add(element.apply(instrumentedType,
                                instrumentedMethod,
                                target,
                                arguments,
                                result,
                                assigner,
                                argumentOffsets,
                                result.represents(void.class)
                                        ? StackManipulation.Trivial.INSTANCE
                                        : MethodVariableAccess.of(result).loadFrom(offset)));
                    }
                    stackManipulations.add(result.represents(void.class)
                            ? StackManipulation.Trivial.INSTANCE
                            : MethodVariableAccess.of(result).loadFrom(offset));
                    return new Binding(new StackManipulation.Compound(stackManipulations), offset);
                }

                interface Element {

                    StackManipulation apply(TypeDescription instrumentedType,
                                            MethodDescription instrumentedMethod,
                                            ByteCodeElement target,
                                            TypeList.Generic arguments,
                                            TypeDescription.Generic result,
                                            Assigner assigner,
                                            Map<Integer, Integer> argumentOffsets,
                                            StackManipulation previousResult);
                }
            }
        }

        /**
         * Determines a method's invocation type.
         */
        enum InvocationType {

            /**
             * Indicates that a method is called virtually.
             */
            VIRTUAL,

            /**
             * Indicates that a method is called via a super method call.
             */
            SUPER,

            /**
             * Indicates that an invoked method is not a virtual method.
             */
            OTHER;

            /**
             * Creates an invocation type.
             *
             * @param opcode            The method call's opcode.
             * @param methodDescription The method being invoked.
             * @return The method's invocation type.
             */
            protected static InvocationType of(int opcode, MethodDescription methodDescription) {
                switch (opcode) {
                    case Opcodes.INVOKEVIRTUAL:
                    case Opcodes.INVOKEINTERFACE:
                        return InvocationType.VIRTUAL;
                    case Opcodes.INVOKESPECIAL:
                        return methodDescription.isVirtual()
                                ? SUPER
                                : OTHER;
                    default:
                        return OTHER;
                }
            }

            /**
             * Determines if a method is matched by this invocation type.
             *
             * @param includeVirtualCalls {@code true} if virtual calls are included.
             * @param includeSuperCalls   {@code true} if super method calls are included.
             * @return {@code true} if this instance matches the given setup.
             */
            protected boolean matches(boolean includeVirtualCalls, boolean includeSuperCalls) {
                switch (this) {
                    case VIRTUAL:
                        return includeVirtualCalls;
                    case SUPER:
                        return includeSuperCalls;
                    default:
                        return true;
                }
            }
        }

        /**
         * A substution that does not substitute any byte code elements.
         */
        enum NoOp implements Substitution {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public Resolver resolve(FieldDescription.InDefinedShape fieldDescription, boolean writeAccess) {
                return Resolver.Unresolved.INSTANCE;
            }

            @Override
            public Resolver resolve(MethodDescription methodDescription, InvocationType invocationType) {
                return Resolver.Unresolved.INSTANCE;
            }
        }

        /**
         * A substitution that uses element matchers for determining if a byte code element should be substituted.
         */
        @EqualsAndHashCode
        class ForElementMatchers implements Substitution {

            /**
             * A matcher to determine field substitution.
             */
            private final ElementMatcher<? super FieldDescription.InDefinedShape> fieldMatcher;

            /**
             * A matcher to determine method substituion.
             */
            private final ElementMatcher<? super MethodDescription> methodMatcher;

            /**
             * {@code true} if field read access should be substituted.
             */
            private final boolean matchFieldRead;

            /**
             * {@code true} if field write access should be substituted.
             */
            private final boolean matchFieldWrite;

            /**
             * {@code true} if virtual method calls should be substituted.
             */
            private final boolean includeVirtualCalls;

            /**
             * {@code true} if super method calls should be substituted.
             */
            private final boolean includeSuperCalls;

            /**
             * The resolver to apply on elements to substitute.
             */
            private final Resolver resolver;

            /**
             * Creates a substitution for any byte code element that matches the supplied matcher.
             *
             * @param matcher  The matcher to determine the substituted byte code elements.
             * @param resolver The resolver to apply on elements to substitute.
             * @return A substitution for all matched byte code elements.
             */
            protected static Substitution of(ElementMatcher<? super ByteCodeElement> matcher, Resolver resolver) {
                return new ForElementMatchers(matcher, matcher, true, true, true, true, resolver);
            }

            /**
             * Creates a substitution for any method that matches the supplied matcher.
             *
             * @param matcher         The matcher to determine the substituted fields.
             * @param matchFieldRead  {@code true} if field read access should be substituted.
             * @param matchFieldWrite {@code true} if field write access should be substituted.
             * @param resolver        The resolver to apply on fields to substitute.
             * @return A substitution for all matched fields.
             */
            protected static Substitution ofField(ElementMatcher<? super FieldDescription.InDefinedShape> matcher,
                                                  boolean matchFieldRead,
                                                  boolean matchFieldWrite,
                                                  Resolver resolver) {
                return new ForElementMatchers(matcher, none(), matchFieldRead, matchFieldWrite, false, false, resolver);
            }

            /**
             * Creates a substitution for any method that matches the supplied matcher.
             *
             * @param matcher             The matcher to determine the substituted fields.
             * @param includeVirtualCalls {@code true} if virtual method calls should be substituted.
             * @param includeSuperCalls   {@code true} if super method calls should be substituted.
             * @param resolver            The resolver to apply on fields to substitute.
             * @return A substitution for all matched fields.
             */
            protected static Substitution ofMethod(ElementMatcher<? super MethodDescription> matcher,
                                                   boolean includeVirtualCalls,
                                                   boolean includeSuperCalls,
                                                   Resolver resolver) {
                return new ForElementMatchers(none(), matcher, false, false, includeVirtualCalls, includeSuperCalls, resolver);
            }

            /**
             * Creates a new subsitution that applies element matchers to determine what byte code elements to substitute.
             *
             * @param fieldMatcher        The field matcher to determine fields to substitute.
             * @param methodMatcher       The method matcher to determine methods to substitute.
             * @param matchFieldRead      {@code true} if field read access should be substituted.
             * @param matchFieldWrite     {@code true} if field write access should be substituted.
             * @param includeVirtualCalls {@code true} if virtual method calls should be substituted.
             * @param includeSuperCalls   {@code true} if super method calls should be substituted.
             * @param resolver            The resolver to apply on elements to substitute.
             */
            protected ForElementMatchers(ElementMatcher<? super FieldDescription.InDefinedShape> fieldMatcher,
                                         ElementMatcher<? super MethodDescription> methodMatcher,
                                         boolean matchFieldRead,
                                         boolean matchFieldWrite,
                                         boolean includeVirtualCalls,
                                         boolean includeSuperCalls,
                                         Resolver resolver) {
                this.fieldMatcher = fieldMatcher;
                this.methodMatcher = methodMatcher;
                this.matchFieldRead = matchFieldRead;
                this.matchFieldWrite = matchFieldWrite;
                this.includeVirtualCalls = includeVirtualCalls;
                this.includeSuperCalls = includeSuperCalls;
                this.resolver = resolver;
            }

            @Override
            public Resolver resolve(FieldDescription.InDefinedShape fieldDescription, boolean writeAccess) {
                return (writeAccess ? matchFieldWrite : matchFieldRead) && fieldMatcher.matches(fieldDescription)
                        ? resolver
                        : Resolver.Unresolved.INSTANCE;
            }

            @Override
            public Resolver resolve(MethodDescription methodDescription, InvocationType invocationType) {
                return invocationType.matches(includeVirtualCalls, includeSuperCalls) && methodMatcher.matches(methodDescription)
                        ? resolver
                        : Resolver.Unresolved.INSTANCE;
            }
        }

        /**
         * A compound substitution.
         */
        @EqualsAndHashCode
        class Compound implements Substitution {

            /**
             * The substitutions to apply in their application order.
             */
            private final List<Substitution> substitutions;

            /**
             * Creates a new compound substitution.
             *
             * @param substitution The substitutions to apply in their application order.
             */
            protected Compound(Substitution... substitution) {
                this(Arrays.asList(substitution));
            }

            /**
             * Creates a new compound substitution.
             *
             * @param substitutions The substitutions to apply in their application order.
             */
            protected Compound(List<? extends Substitution> substitutions) {
                this.substitutions = new ArrayList<Substitution>(substitutions.size());
                for (Substitution substitution : substitutions) {
                    if (substitution instanceof Compound) {
                        this.substitutions.addAll(((Compound) substitution).substitutions);
                    } else if (!(substitution instanceof NoOp)) {
                        this.substitutions.add(substitution);
                    }
                }
            }

            @Override
            public Resolver resolve(FieldDescription.InDefinedShape fieldDescription, boolean writeAccess) {
                for (Substitution substitution : substitutions) {
                    Resolver resolver = substitution.resolve(fieldDescription, writeAccess);
                    if (resolver.isResolved()) {
                        return resolver;
                    }
                }
                return Resolver.Unresolved.INSTANCE;
            }

            @Override
            public Resolver resolve(MethodDescription methodDescription, InvocationType invocationType) {
                for (Substitution substitution : substitutions) {
                    Resolver resolver = substitution.resolve(methodDescription, invocationType);
                    if (resolver.isResolved()) {
                        return resolver;
                    }
                }
                return Resolver.Unresolved.INSTANCE;
            }
        }
    }

    /**
     * A method visitor that applies a substitution for matched methods.
     */
    protected static class SubstitutingMethodVisitor extends LocalVariableAwareMethodVisitor {

        /**
         * The method graph compiler to use.
         */
        private final MethodGraph.Compiler methodGraphCompiler;

        /**
         * {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
         */
        private final boolean strict;

        /**
         * The substitution to apply.
         */
        private final Substitution substitution;

        /**
         * The instrumented type.
         */
        private final TypeDescription instrumentedType;

        private final MethodDescription instrumentedMethod;

        /**
         * The implementation context to use.
         */
        private final Implementation.Context implementationContext;

        /**
         * The type pool to use.
         */
        private final TypePool typePool;

        /**
         * An additional buffer for the operand stack that is required.
         */
        private int stackSizeBuffer;

        /**
         * Creates a new substituting method visitor.
         *
         * @param methodVisitor         The method visitor to delegate to.
         * @param methodGraphCompiler   The method graph compiler to use.
         * @param strict                {@code true} if the method processing should be strict where an exception is raised if a member cannot be found.
         * @param substitution          The substitution to apply.
         * @param instrumentedType      The instrumented type.
         * @param instrumentedMethod
         * @param implementationContext The implementation context to use.
         * @param typePool              The type pool to use.
         */
        protected SubstitutingMethodVisitor(MethodVisitor methodVisitor,
                                            MethodGraph.Compiler methodGraphCompiler,
                                            boolean strict,
                                            Substitution substitution,
                                            TypeDescription instrumentedType,
                                            MethodDescription instrumentedMethod,
                                            Implementation.Context implementationContext,
                                            TypePool typePool) {
            super(methodVisitor, instrumentedMethod);
            this.methodGraphCompiler = methodGraphCompiler;
            this.strict = strict;
            this.substitution = substitution;
            this.instrumentedType = instrumentedType;
            this.instrumentedMethod = instrumentedMethod;
            this.implementationContext = implementationContext;
            this.typePool = typePool;
            stackSizeBuffer = 0;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String internalName, String descriptor) {
            TypePool.Resolution resolution = typePool.describe(owner.replace('/', '.'));
            if (resolution.isResolved()) {
                FieldList<FieldDescription.InDefinedShape> candidates = resolution.resolve()
                        .getDeclaredFields()
                        .filter(named(internalName).and(hasDescriptor(descriptor)));
                if (!candidates.isEmpty()) {
                    Substitution.Resolver resolver = substitution.resolve(candidates.getOnly(), opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC);
                    if (resolver.isResolved()) {
                        TypeList.Generic arguments;
                        TypeDescription.Generic result;
                        StackManipulation.Size base;
                        switch (opcode) {
                            case Opcodes.PUTFIELD:
                                arguments = new TypeList.Generic.Explicit(candidates.getOnly().getDeclaringType(), candidates.getOnly().getType());
                                result = TypeDescription.Generic.VOID;
                                base = new StackManipulation.Size(candidates.getOnly().getType().getStackSize().getSize() + 1);
                                break;
                            case Opcodes.PUTSTATIC:
                                arguments = new TypeList.Generic.Explicit(candidates.getOnly().getType());
                                result = TypeDescription.Generic.VOID;
                                base = new StackManipulation.Size(candidates.getOnly().getType().getStackSize().getSize());
                                break;
                            case Opcodes.GETFIELD:
                                arguments = new TypeList.Generic.Explicit(candidates.getOnly().getDeclaringType());
                                result = candidates.getOnly().getType();
                                base = new StackManipulation.Size(1);
                                break;
                            case Opcodes.GETSTATIC:
                                arguments = new TypeList.Generic.Empty();
                                result = candidates.getOnly().getType();
                                base = new StackManipulation.Size(0);
                                break;
                            default:
                                throw new AssertionError();
                        }
                        Substitution.Resolver.Binding binding = resolver.apply(instrumentedType,
                                instrumentedMethod,
                                candidates.getOnly(),
                                arguments,
                                result,
                                getOffsetLimit());
                        stackSizeBuffer = Math.max(stackSizeBuffer, base.aggregate(binding.getStackManipulation()
                                .apply(mv, implementationContext))
                                .getMaximalSize() - base.getMaximalSize());
                        requireMinimumOffset(binding.getOffset());
                        return;
                    }
                } else if (strict) {
                    throw new IllegalStateException("Could not resolve " + owner.replace('/', '.')
                            + "." + internalName + descriptor + " using " + typePool);
                }
            } else if (strict) {
                throw new IllegalStateException("Could not resolve " + owner.replace('/', '.') + " using " + typePool);
            }
            super.visitFieldInsn(opcode, owner, internalName, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String internalName, String descriptor, boolean isInterface) {
            TypePool.Resolution resolution = typePool.describe(owner.replace('/', '.'));
            if (resolution.isResolved()) {
                MethodList<?> candidates;
                if (opcode == Opcodes.INVOKESPECIAL && internalName.equals(MethodDescription.CONSTRUCTOR_INTERNAL_NAME)) {
                    candidates = resolution.resolve()
                            .getDeclaredMethods()
                            .filter(isConstructor().and(hasDescriptor(descriptor)));
                } else if (opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL) {
                    candidates = resolution.resolve()
                            .getDeclaredMethods()
                            .filter(named(internalName).and(hasDescriptor(descriptor)));
                } else {
                    candidates = methodGraphCompiler.compile(resolution.resolve())
                            .listNodes()
                            .asMethodList()
                            .filter(named(internalName).and(hasDescriptor(descriptor)));
                }
                if (!candidates.isEmpty()) {
                    Substitution.Resolver resolver = substitution.resolve(candidates.getOnly(), Substitution.InvocationType.of(opcode, candidates.getOnly()));
                    if (resolver.isResolved()) {
                        StackManipulation.Size base;
                        if (candidates.getOnly().isStatic()) {
                            base = new StackManipulation.Size(candidates.getOnly().getParameters().asTypeList().getStackSize());
                        } else if (candidates.getOnly().isConstructor()) {
                            base = new StackManipulation.Size(candidates.getOnly().getParameters().asTypeList().getStackSize() + 2);
                        } else {
                            base = new StackManipulation.Size(candidates.getOnly().getParameters().asTypeList().getStackSize() + 1);
                        }
                        Substitution.Resolver.Binding binding = resolver.apply(instrumentedType,
                                instrumentedMethod,
                                candidates.getOnly(),
                                candidates.getOnly().isStatic() || candidates.getOnly().isConstructor()
                                        ? candidates.getOnly().getParameters().asTypeList()
                                        : new TypeList.Generic.Explicit(CompoundList.of(candidates.getOnly().getDeclaringType(), candidates.getOnly().getParameters().asTypeList())),
                                candidates.getOnly().isConstructor()
                                        ? candidates.getOnly().getDeclaringType().asGenericType()
                                        : candidates.getOnly().getReturnType(),
                                getOffsetLimit());
                        StackManipulation.Size size = base.aggregate(binding.getStackManipulation().apply(mv, implementationContext));
                        if (candidates.getOnly().isConstructor()) {
                            size = size.aggregate(new StackManipulation.Compound(
                                    Duplication.WithFlip.SINGLE_SINGLE,
                                    Removal.SINGLE,
                                    Removal.SINGLE,
                                    Duplication.WithFlip.SINGLE_SINGLE,
                                    Removal.SINGLE,
                                    Removal.SINGLE
                            ).apply(mv, implementationContext));
                        }
                        stackSizeBuffer = Math.max(stackSizeBuffer, size.getMaximalSize() - base.getMaximalSize());
                        requireMinimumOffset(binding.getOffset());
                        return;
                    }
                } else if (strict) {
                    throw new IllegalStateException("Could not resolve " + owner.replace('/', '.')
                            + "." + internalName + descriptor + " using " + typePool);
                }
            } else if (strict) {
                throw new IllegalStateException("Could not resolve " + owner.replace('/', '.') + " using " + typePool);
            }
            super.visitMethodInsn(opcode, owner, internalName, descriptor, isInterface);
        }

        @Override
        public void visitMaxs(int stackSize, int localVariableLength) {
            super.visitMaxs(stackSize + stackSizeBuffer, localVariableLength);
        }
    }
}
