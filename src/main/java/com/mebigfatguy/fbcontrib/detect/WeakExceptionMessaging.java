/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2017 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fbcontrib.detect;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

import org.apache.bcel.Const;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.ConstantString;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.SignatureBuilder;
import com.mebigfatguy.fbcontrib.utils.SignatureUtils;
import com.mebigfatguy.fbcontrib.utils.TernaryPatcher;
import com.mebigfatguy.fbcontrib.utils.UnmodifiableSet;
import com.mebigfatguy.fbcontrib.utils.Values;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.CustomUserValue;
import edu.umd.cs.findbugs.ba.ClassContext;

/**
 * looks for exceptions that are thrown with static strings as messages. Using static strings doesn't differentiate one use of this method versus another, and
 * so it may be difficult to determine how this exception occurred without showing context.
 */
@CustomUserValue
public class WeakExceptionMessaging extends BytecodeScanningDetector {

    private static JavaClass exceptionClass;
    private static final Set<String> ignorableExceptionTypes = UnmodifiableSet.create("java.lang.UnsupportedOperationException");

    static {
        try {
            exceptionClass = Repository.lookupClass(Values.SLASHED_JAVA_LANG_EXCEPTION);
        } catch (ClassNotFoundException cnfe) {
            exceptionClass = null;
        }
    }

    private final BugReporter bugReporter;
    private OpcodeStack stack;

    /**
     * constructs a WEM detector given the reporter to report bugs on
     *
     * @param bugReporter
     *            the sync of bug reports
     */
    public WeakExceptionMessaging(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    /**
     * overrides the visitor to initialize and tear down the opcode stack
     *
     * @param classContext
     *            the context object of the currently parsed class
     */
    @Override
    public void visitClassContext(ClassContext classContext) {
        try {
            if (exceptionClass != null) {
                stack = new OpcodeStack();
                super.visitClassContext(classContext);
            }
        } finally {
            stack = null;
        }
    }

    /**
     * looks for methods that contain a ATHROW opcodes, ignoring static initializers
     *
     * @param method
     *            the context object of the current method
     * @return if the class uses throws
     */
    public boolean prescreen(Method method) {
        if (Values.STATIC_INITIALIZER.equals(method.getName())) {
            return false;
        }

        BitSet bytecodeSet = getClassContext().getBytecodeSet(method);
        return (bytecodeSet != null) && (bytecodeSet.get(Const.ATHROW));
    }

    /**
     * overrides the visitor to prescreen the method to look for throws calls and only forward onto bytecode scanning if there
     *
     * @param obj
     *            the context object of the currently parsed code block
     */
    @Override
    public void visitCode(Code obj) {
        Method method = getMethod();
        if (!method.isSynthetic() && prescreen(method)) {
            stack.resetForMethodEntry(this);
            super.visitCode(obj);
        }
    }

    /**
     * overrides the visitor to look for throws instructions using exceptions with static messages
     *
     * @param seen
     *            the opcode of the currently visited instruction
     */
    @Override
    public void sawOpcode(int seen) {
        boolean allConstantStrings = false;
        boolean sawConstant = false;
        try {
            stack.precomputation(this);

            if (seen == ATHROW) {
                checkForWEM();
            } else if ((seen == LDC) || (seen == LDC_W)) {
                if (getConstantRefOperand() instanceof ConstantString) {
                    sawConstant = true;
                }
            } else if ((seen == INVOKESPECIAL) && Values.CONSTRUCTOR.equals(getNameConstantOperand())) {
                String clsName = getClassConstantOperand();
                if (clsName.indexOf("Exception") < 0) {
                    return;
                }
                JavaClass exCls = Repository.lookupClass(clsName);
                if (!exCls.instanceOf(exceptionClass)) {
                    return;
                }
                String sig = getSigConstantOperand();
                List<String> argTypes = SignatureUtils.getParameterSignatures(sig);
                int stringParms = 0;
                for (int t = 0; t < argTypes.size(); t++) {
                    if (!Values.SIG_JAVA_LANG_STRING.equals(argTypes.get(t))) {
                        continue;
                    }
                    stringParms++;
                    int stackOffset = argTypes.size() - t - 1;
                    if ((stack.getStackDepth() > stackOffset) && (stack.getStackItem(stackOffset).getUserValue() == null)) {
                        return;
                    }
                }
                if (Values.SLASHED_JAVA_LANG_EXCEPTION.equals(clsName) && SignatureBuilder.SIG_THROWABLE_TO_VOID.equals(getSigConstantOperand())) {
                    bugReporter.reportBug(
                            new BugInstance(this, BugType.WEM_OBSCURING_EXCEPTION.name(), LOW_PRIORITY).addClass(this).addMethod(this).addSourceLine(this));
                }
                allConstantStrings = stringParms > 0;
            }
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
        } finally {
            TernaryPatcher.pre(stack, seen);
            stack.sawOpcode(this, seen);
            TernaryPatcher.post(stack, seen);
            if ((sawConstant || allConstantStrings) && (stack.getStackDepth() > 0)) {
                OpcodeStack.Item item = stack.getStackItem(0);
                item.setUserValue(Boolean.TRUE);
            }
        }
    }

    private void checkForWEM() throws ClassNotFoundException {
        if (stack.getStackDepth() == 0) {
            return;
        }
        OpcodeStack.Item item = stack.getStackItem(0);
        if (item.getUserValue() == null) {
            return;
        }
        JavaClass exClass = item.getJavaClass();
        if ((exClass == null) || !ignorableExceptionTypes.contains(exClass.getClassName())) {
            bugReporter.reportBug(
                    new BugInstance(this, BugType.WEM_WEAK_EXCEPTION_MESSAGING.name(), LOW_PRIORITY).addClass(this).addMethod(this).addSourceLine(this));
        }
    }
}
