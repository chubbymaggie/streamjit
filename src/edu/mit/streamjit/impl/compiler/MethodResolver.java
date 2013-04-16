package edu.mit.streamjit.impl.compiler;

import com.google.common.collect.ImmutableMap;
import edu.mit.streamjit.api.Identity;
import edu.mit.streamjit.impl.common.MethodNodeBuilder;
import edu.mit.streamjit.impl.compiler.insts.BinaryInst;
import edu.mit.streamjit.impl.compiler.insts.BranchInst;
import edu.mit.streamjit.impl.compiler.insts.CallInst;
import edu.mit.streamjit.impl.compiler.insts.CastInst;
import edu.mit.streamjit.impl.compiler.insts.JumpInst;
import edu.mit.streamjit.impl.compiler.insts.LoadInst;
import edu.mit.streamjit.impl.compiler.insts.ReturnInst;
import edu.mit.streamjit.impl.compiler.types.MethodType;
import edu.mit.streamjit.impl.compiler.types.ReferenceType;
import edu.mit.streamjit.impl.compiler.types.ReturnType;
import edu.mit.streamjit.impl.compiler.types.Type;
import edu.mit.streamjit.impl.compiler.types.TypeFactory;
import edu.mit.streamjit.impl.compiler.types.VoidType;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Resolves methods.
 *
 * This class assumes it's parsing valid bytecode, so it asserts rather than
 * throws on simple checks like "aload_0 is loading a reference type".
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 4/12/2013
 */
public final class MethodResolver {
	public static void resolve(Method m) {
		new MethodResolver(m).resolve();
	}

	private final Method method;
	private final MethodNode methodNode;
	private final List<BBInfo> blocks = new ArrayList<>();
	private final Module module;
	private final TypeFactory typeFactory;
	/**
	 * If we're resolving a constructor, this is the uninitializedThis value.
	 */
	private final UninitializedValue uninitializedThis;
	/**
	 * Used for generating sequential names (e.g., uninitialized object names).
	 */
	private int counter = 1;
	private MethodResolver(Method m) {
		this.method = m;
		this.module = method.getParent().getParent();
		this.typeFactory = module.types();
		try {
			this.methodNode = MethodNodeBuilder.buildMethodNode(method);
		} catch (IOException | NoSuchMethodException ex) {
			throw new RuntimeException(ex);
		}
		if (m.getName().equals("<init>"))
			this.uninitializedThis = new UninitializedValue(typeFactory.getType(m.getParent()), "uninitializedThis");
		else
			this.uninitializedThis = null;
	}

	private void resolve() {
		findBlockBoundaries();

		//Process blocks such that at least one predecessor has already been
		//visited.  (We only process a block once; we add phi instructions when
		//frame merging and replace uses of the previous values in the block.)
		Set<BBInfo> visited = new HashSet<>();
		Queue<BBInfo> worklist = new ArrayDeque<>();
		worklist.add(blocks.get(0));
		while (!worklist.isEmpty()) {
			BBInfo block = worklist.remove();
			buildInstructions(block);
			visited.add(block);
			for (BasicBlock b : block.block.successors()) {
				for (BBInfo bi : blocks)
					if (bi.block == b) {
						if (!visited.contains(bi))
							worklist.add(bi);
						break;
					}
			}
		}
		//I'm assuming there's no trivially dead blocks.
		assert visited.size() == blocks.size();
	}

	private void findBlockBoundaries() {
		InsnList insns = methodNode.instructions;
		//We find the indices of any block-ending instruction and of any jump
		//target, sort, remove duplicates, then use pairs to define blocks. Note
		//these are end-exclusive indices, thus one after the block-enders, but
		//right on the jump targets (they're one-past-the-end of the preceding
		//block).
		List<Integer> indices = new ArrayList<>();
		indices.add(0);
		for (int i = 0; i < insns.size(); ++i) {
			AbstractInsnNode insn = insns.get(i);
			int opcode = insn.getOpcode();
			if (insn instanceof JumpInsnNode || insn instanceof LookupSwitchInsnNode ||
					insn instanceof TableSwitchInsnNode || opcode == Opcodes.ATHROW ||
					opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN ||
					opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN ||
					opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN) {
				indices.add(i+1);
			}
			if (insn instanceof JumpInsnNode)
				indices.add(insns.indexOf(((JumpInsnNode)insn).label));
			else if (insn instanceof LookupSwitchInsnNode) {
				indices.add(insns.indexOf(((LookupSwitchInsnNode)insn).dflt));
				for (Object label : ((LookupSwitchInsnNode)insn).labels)
					indices.add(insns.indexOf((LabelNode)label));
			} else if (insn instanceof TableSwitchInsnNode) {
				indices.add(insns.indexOf(((TableSwitchInsnNode)insn).dflt));
				for (Object label : ((TableSwitchInsnNode)insn).labels)
					indices.add(insns.indexOf((LabelNode)label));
			}
		}

		//Remove duplicates and sort via TreeSet.
		indices = new ArrayList<>(new TreeSet<>(indices));
		for (int i = 1; i < indices.size(); ++i)
			blocks.add(new BBInfo(indices.get(i-1), indices.get(i)));
	}

	private void buildInstructions(BBInfo block) {
		FrameState frame = block.entryState.copy();
		for (int i = block.start; i < block.end; ++i) {
			AbstractInsnNode insn = methodNode.instructions.get(i);
			if (insn.getOpcode() == -1) continue;//pseudo-instruction node
			if (insn instanceof FieldInsnNode)
				interpret((FieldInsnNode)insn, frame, block);
			else if (insn instanceof IincInsnNode)
				interpret((IincInsnNode)insn, frame, block);
			else if (insn instanceof InsnNode)
				interpret((InsnNode)insn, frame, block);
			else if (insn instanceof IntInsnNode)
				interpret((IntInsnNode)insn, frame, block);
			else if (insn instanceof InvokeDynamicInsnNode)
				interpret((InvokeDynamicInsnNode)insn, frame, block);
			else if (insn instanceof JumpInsnNode)
				interpret((JumpInsnNode)insn, frame, block);
			else if (insn instanceof LdcInsnNode)
				interpret((LdcInsnNode)insn, frame, block);
			else if (insn instanceof LookupSwitchInsnNode)
				interpret((LookupSwitchInsnNode)insn, frame, block);
			else if (insn instanceof MethodInsnNode)
				interpret((MethodInsnNode)insn, frame, block);
			else if (insn instanceof MultiANewArrayInsnNode)
				interpret((MultiANewArrayInsnNode)insn, frame, block);
			else if (insn instanceof TableSwitchInsnNode)
				interpret((TableSwitchInsnNode)insn, frame, block);
			else if (insn instanceof TypeInsnNode)
				interpret((TypeInsnNode)insn, frame, block);
			else if (insn instanceof VarInsnNode)
				interpret((VarInsnNode)insn, frame, block);
		}

		//If the block doesn't have a TerminatorInst, add a JumpInst to the
		//fallthrough block.  (This occurs when blocks begin due to being a
		//jump target rather than due to a terminator opcode.)
		if (block.block.getTerminator() == null)
			block.block.instructions().add(new JumpInst(blocks.get(blocks.indexOf(block)+1).block));

		for (BasicBlock b : block.block.successors())
			for (BBInfo bi : blocks)
				if (bi.block == b) {
					merge(frame, bi);
					break;
				}
	}

	private void interpret(FieldInsnNode insn, FrameState frame, BBInfo block) {
		Klass k = getKlassByInternalName(insn.owner);
		Field f = k.getField(insn.name);
		switch (insn.getOpcode()) {
			case Opcodes.GETSTATIC:
				LoadInst li = new LoadInst(f);
				block.block.instructions().add(li);
				frame.stack.push(li);
				break;
			case Opcodes.GETFIELD:
				LoadInst li2 = new LoadInst(f, frame.stack.pop());
				block.block.instructions().add(li2);
				frame.stack.push(li2);
				break;
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	private void interpret(IincInsnNode insn, FrameState frame, BBInfo block) {
		switch (insn.getOpcode()) {
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	private void interpret(InsnNode insn, FrameState frame, BBInfo block) {
		ReturnType returnType = block.block.getParent().getType().getReturnType();
		switch (insn.getOpcode()) {
			case Opcodes.NOP:
				break;
			//<editor-fold defaultstate="collapsed" desc="Stack manipulation opcodes (pop, dup, swap)">
			case Opcodes.POP:
				assert categoryOf(frame.stack.peek().getType()) == 1;
				frame.stack.pop();
				break;
			case Opcodes.POP2:
				final int[][][] pop2Permutations = {
					{{1,1}, {}},
					{{2}, {}}
				};
				conditionallyPermute(frame, pop2Permutations);
				break;
			case Opcodes.DUP:
				final int[][][] dupPermutations = {{{1}, {1,1}}};
				conditionallyPermute(frame, dupPermutations);
				break;
			case Opcodes.DUP_X1:
				final int[][][] dup_x1Permutations = {{{1,2}, {1,2,1}}};
				conditionallyPermute(frame, dup_x1Permutations);
				break;
			case Opcodes.DUP_X2:
				final int[][][] dup_x2Permutations = {
					{{1,1,1}, {1,3,2,1}},
					{{1,2}, {1,2,1}}
				};
				conditionallyPermute(frame, dup_x2Permutations);
				break;
			case Opcodes.DUP2:
				final int[][][] dup2Permutations = {
					{{1,1}, {2,1,2,1}},
					{{2}, {1,1}}
				};
				conditionallyPermute(frame, dup2Permutations);
				break;
			case Opcodes.DUP2_X1:
				final int[][][] dup2_x1Permutations = {
					{{1,1,1}, {2,1,3,2,1}},
					{{2,1}, {1,2,1}}
				};
				conditionallyPermute(frame, dup2_x1Permutations);
				break;
			case Opcodes.DUP2_X2:
				final int[][][] dup2_x2Permutations = {
					{{1,1,1,1}, {2,1,4,3,2,1}},
					{{2,1,1}, {1,3,2,1}},
					{{3,2,1}, {2,1,3,2,1}},
					{{2,2}, {1,2,1}}
				};
				conditionallyPermute(frame, dup2_x2Permutations);
				break;
			case Opcodes.SWAP:
				final int[][][] swapPermutations = {{{1,1}, {1,2}}};
				conditionallyPermute(frame, swapPermutations);
				break;
			//</editor-fold>
			//<editor-fold defaultstate="collapsed" desc="Constant-stacking opcodes (iconst_0, etc.; see also bipush, sipush)">
			case Opcodes.ACONST_NULL:
				frame.stack.push(module.constants().getNullConstant());
				break;
			case Opcodes.ICONST_M1:
				frame.stack.push(module.constants().getSmallestIntConstant(-1));
				break;
			case Opcodes.ICONST_0:
				frame.stack.push(module.constants().getSmallestIntConstant(0));
				break;
			case Opcodes.ICONST_1:
				frame.stack.push(module.constants().getSmallestIntConstant(1));
				break;
			case Opcodes.ICONST_2:
				frame.stack.push(module.constants().getSmallestIntConstant(2));
				break;
			case Opcodes.ICONST_3:
				frame.stack.push(module.constants().getSmallestIntConstant(3));
				break;
			case Opcodes.ICONST_4:
				frame.stack.push(module.constants().getSmallestIntConstant(4));
				break;
			case Opcodes.ICONST_5:
				frame.stack.push(module.constants().getSmallestIntConstant(5));
				break;
			case Opcodes.LCONST_0:
				frame.stack.push(module.constants().getConstant(0L));
				break;
			case Opcodes.LCONST_1:
				frame.stack.push(module.constants().getConstant(1L));
				break;
			case Opcodes.FCONST_0:
				frame.stack.push(module.constants().getConstant(0f));
				break;
			case Opcodes.FCONST_1:
				frame.stack.push(module.constants().getConstant(1f));
				break;
			case Opcodes.FCONST_2:
				frame.stack.push(module.constants().getConstant(2f));
				break;
			case Opcodes.DCONST_0:
				frame.stack.push(module.constants().getConstant(0d));
				break;
			case Opcodes.DCONST_1:
				frame.stack.push(module.constants().getConstant(1d));
				break;
			//</editor-fold>
			//<editor-fold defaultstate="collapsed" desc="Return opcodes">
			case Opcodes.IRETURN:
				assert returnType.isSubtypeOf(typeFactory.getType(int.class));
				assert frame.stack.peek().getType().isSubtypeOf(returnType);
				block.block.instructions().add(new ReturnInst(returnType, frame.stack.pop()));
				break;
			case Opcodes.LRETURN:
				assert returnType.isSubtypeOf(typeFactory.getType(long.class));
				assert frame.stack.peek().getType().isSubtypeOf(returnType);
				block.block.instructions().add(new ReturnInst(returnType, frame.stack.pop()));
				break;
			case Opcodes.FRETURN:
				assert returnType.isSubtypeOf(typeFactory.getType(float.class));
				assert frame.stack.peek().getType().isSubtypeOf(returnType);
				block.block.instructions().add(new ReturnInst(returnType, frame.stack.pop()));
				break;
			case Opcodes.DRETURN:
				assert returnType.isSubtypeOf(typeFactory.getType(double.class));
				assert frame.stack.peek().getType().isSubtypeOf(returnType);
				block.block.instructions().add(new ReturnInst(returnType, frame.stack.pop()));
				break;
			case Opcodes.ARETURN:
				assert returnType.isSubtypeOf(typeFactory.getType(Object.class));
				assert frame.stack.peek().getType().isSubtypeOf(returnType);
				block.block.instructions().add(new ReturnInst(returnType, frame.stack.pop()));
				break;
			case Opcodes.RETURN:
				assert returnType instanceof VoidType;
				block.block.instructions().add(new ReturnInst(returnType));
				break;
			//</editor-fold>
			//<editor-fold defaultstate="collapsed" desc="Binary math opcodes">
			case Opcodes.IADD:
			case Opcodes.LADD:
			case Opcodes.FADD:
			case Opcodes.DADD:
				binary(BinaryInst.Operation.ADD, frame, block);
				break;
			case Opcodes.ISUB:
			case Opcodes.LSUB:
			case Opcodes.FSUB:
			case Opcodes.DSUB:
				binary(BinaryInst.Operation.SUB, frame, block);
				break;
			case Opcodes.IMUL:
			case Opcodes.LMUL:
			case Opcodes.FMUL:
			case Opcodes.DMUL:
				binary(BinaryInst.Operation.MUL, frame, block);
				break;
			case Opcodes.IDIV:
			case Opcodes.LDIV:
			case Opcodes.FDIV:
			case Opcodes.DDIV:
				binary(BinaryInst.Operation.DIV, frame, block);
				break;
			case Opcodes.IREM:
			case Opcodes.LREM:
			case Opcodes.FREM:
			case Opcodes.DREM:
				binary(BinaryInst.Operation.REM, frame, block);
				break;
			case Opcodes.ISHL:
			case Opcodes.LSHL:
				binary(BinaryInst.Operation.SHL, frame, block);
				break;
			case Opcodes.ISHR:
			case Opcodes.LSHR:
				binary(BinaryInst.Operation.SHR, frame, block);
				break;
			case Opcodes.IUSHR:
			case Opcodes.LUSHR:
				binary(BinaryInst.Operation.USHR, frame, block);
				break;
			case Opcodes.IAND:
			case Opcodes.LAND:
				binary(BinaryInst.Operation.AND, frame, block);
				break;
			case Opcodes.IOR:
			case Opcodes.LOR:
				binary(BinaryInst.Operation.OR, frame, block);
				break;
			case Opcodes.IXOR:
			case Opcodes.LXOR:
				binary(BinaryInst.Operation.XOR, frame, block);
				break;
			case Opcodes.LCMP:
			case Opcodes.FCMPL:
			case Opcodes.DCMPL:
				binary(BinaryInst.Operation.CMP, frame, block);
				break;
			case Opcodes.FCMPG:
			case Opcodes.DCMPG:
				binary(BinaryInst.Operation.CMPG, frame, block);
				break;
			//</editor-fold>
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	private void interpret(IntInsnNode insn, FrameState frame, BBInfo block) {
		switch (insn.getOpcode()) {
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	private void interpret(InvokeDynamicInsnNode insn, FrameState frame, BBInfo block) {
		switch (insn.getOpcode()) {
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	//<editor-fold defaultstate="collapsed" desc="JumpInsnNode (goto and branches)">
	private static final ImmutableMap<Integer, BranchInst.Sense> OPCODE_TO_SENSE = ImmutableMap.<Integer, BranchInst.Sense>builder()
			.put(Opcodes.IFEQ, BranchInst.Sense.EQ)
			.put(Opcodes.IFNE, BranchInst.Sense.NE)
			.put(Opcodes.IFLT, BranchInst.Sense.LT)
			.put(Opcodes.IFGE, BranchInst.Sense.GE)
			.put(Opcodes.IFGT, BranchInst.Sense.GT)
			.put(Opcodes.IFLE, BranchInst.Sense.LE)
			.put(Opcodes.IF_ICMPEQ, BranchInst.Sense.EQ)
			.put(Opcodes.IF_ICMPNE, BranchInst.Sense.NE)
			.put(Opcodes.IF_ICMPLT, BranchInst.Sense.LT)
			.put(Opcodes.IF_ICMPGE, BranchInst.Sense.GE)
			.put(Opcodes.IF_ICMPGT, BranchInst.Sense.GT)
			.put(Opcodes.IF_ICMPLE, BranchInst.Sense.LE)
			.put(Opcodes.IF_ACMPEQ, BranchInst.Sense.EQ)
			.put(Opcodes.IF_ACMPNE, BranchInst.Sense.NE)
			.put(Opcodes.IFNULL, BranchInst.Sense.EQ)
			.put(Opcodes.IFNONNULL, BranchInst.Sense.NE)
			.build();
	private void interpret(JumpInsnNode insn, FrameState frame, BBInfo block) {
		//All JumpInsnNodes have a target.  Find it.
		BBInfo target = null;
		int targetIdx = methodNode.instructions.indexOf(insn.label);
		for (BBInfo b : blocks)
			if (b.start <= targetIdx && targetIdx < b.end)
				target = b;
		assert target != null;

		if (insn.getOpcode() == Opcodes.GOTO) {
			block.block.instructions().add(new JumpInst(target.block));
			return;
		} else if (insn.getOpcode() == Opcodes.JSR)
			throw new UnsupportedOperationException("jsr not supported; upgrade to Java 6-era class files");

		//Remaining opcodes are branches.
		BBInfo fallthrough = blocks.get(blocks.indexOf(block)+1);
		BranchInst.Sense sense = OPCODE_TO_SENSE.get(insn.getOpcode());
		//The second operand may come from the stack or may be a constant 0 or null.
		Value right;
		switch (insn.getOpcode()) {
			case Opcodes.IFEQ:
			case Opcodes.IFNE:
			case Opcodes.IFLT:
			case Opcodes.IFGE:
			case Opcodes.IFGT:
			case Opcodes.IFLE:
				right = module.constants().getConstant(0);
				break;
			case Opcodes.IFNULL:
			case Opcodes.IFNONNULL:
				right = module.constants().getNullConstant();
				break;
			case Opcodes.IF_ICMPEQ:
			case Opcodes.IF_ICMPNE:
			case Opcodes.IF_ICMPLT:
			case Opcodes.IF_ICMPGE:
			case Opcodes.IF_ICMPGT:
			case Opcodes.IF_ICMPLE:
			case Opcodes.IF_ACMPEQ:
			case Opcodes.IF_ACMPNE:
				right = frame.stack.pop();
				break;
			default:
				throw new AssertionError("Can't happen! Branch opcode missing? "+insn.getOpcode());
		}
		//First operand always comes from the stack.
		Value left = frame.stack.pop();
		block.block.instructions().add(new BranchInst(left, sense, right, target.block, fallthrough.block));
	}
	//</editor-fold>
	private void interpret(LdcInsnNode insn, FrameState frame, BBInfo block) {
		switch (insn.getOpcode()) {
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	private void interpret(LookupSwitchInsnNode insn, FrameState frame, BBInfo block) {
		switch (insn.getOpcode()) {
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	private void interpret(MethodInsnNode insn, FrameState frame, BBInfo block) {
		Klass k = getKlassByInternalName(insn.owner);
		MethodType mt = typeFactory.getMethodType(insn.desc);
		Method m;
		if (insn.getOpcode() == Opcodes.INVOKESTATIC)
			m = k.getMethod(insn.name, mt);
		else if (insn.getOpcode() == Opcodes.INVOKESPECIAL && insn.name.equals("<init>")) {
			//TODO: invokespecial rules are more complex than this
			//We consider constructors to return their type.
			mt = mt.withReturnType(typeFactory.getType(k));
			m = k.getMethod(insn.name, mt);
		} else {
			//The receiver argument is not in the descriptor, but we represent it in
			//the IR type system.
			if (insn.getOpcode() != Opcodes.INVOKESTATIC)
				mt = mt.prependArgument(typeFactory.getRegularType(k));
			m = k.getMethodByVirtual(insn.name, mt);
		}
		CallInst inst = new CallInst(m);
		block.block.instructions().add(inst);

		//Args are pushed from left-to-right, popped from right-to-left.
		for (int i = mt.getParameterTypes().size()-1; i >= 0; --i)
			inst.setArgument(i, frame.stack.pop());

		//If we called a ctor, we have an uninit object on the stack.  Replace
		//it with the constructed object, or with uninitializedThis if we're a
		//ctor ourselves.
		if (insn.name.equals("<init>")) {
			Value replacement = method.getName().equals("<init>") ? uninitializedThis : inst;
			Value toBeReplaced = frame.stack.pop();
			assert toBeReplaced instanceof UninitializedValue;
			frame.replace(toBeReplaced, replacement);
		} else if (!(mt.getReturnType() instanceof VoidType))
			frame.stack.push(inst);
	}
	private void interpret(MultiANewArrayInsnNode insn, FrameState frame, BBInfo block) {
		switch (insn.getOpcode()) {
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	private void interpret(TableSwitchInsnNode insn, FrameState frame, BBInfo block) {
		switch (insn.getOpcode()) {
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	private void interpret(TypeInsnNode insn, FrameState frame, BBInfo block) {
		ReferenceType t = typeFactory.getReferenceType(getKlassByInternalName(insn.desc));
		switch (insn.getOpcode()) {
			case Opcodes.NEW:
				frame.stack.push(new UninitializedValue(t, "uninit"+(counter++)));
				break;
			case Opcodes.CHECKCAST:
				CastInst c = new CastInst(t, frame.stack.pop());
				block.block.instructions().add(c);
				frame.stack.push(c);
				break;
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}
	private void interpret(VarInsnNode insn, FrameState frame, BBInfo block) {
		int var = insn.var;
		switch (insn.getOpcode()) {
			case Opcodes.ILOAD:
				assert frame.locals[var].getType().isSubtypeOf(typeFactory.getType(int.class));
				frame.stack.push(frame.locals[var]);
				break;
			case Opcodes.LLOAD:
				assert frame.locals[var].getType().isSubtypeOf(typeFactory.getType(long.class));
				frame.stack.push(frame.locals[var]);
				break;
			case Opcodes.FLOAD:
				assert frame.locals[var].getType().isSubtypeOf(typeFactory.getType(float.class));
				frame.stack.push(frame.locals[var]);
				break;
			case Opcodes.DLOAD:
				assert frame.locals[var].getType().isSubtypeOf(typeFactory.getType(double.class));
				frame.stack.push(frame.locals[var]);
				break;
			case Opcodes.ALOAD:
				assert frame.locals[var].getType() instanceof ReferenceType;
				frame.stack.push(frame.locals[var]);
				break;
			case Opcodes.ISTORE:
				assert frame.stack.peek().getType().isSubtypeOf(typeFactory.getType(int.class));
				frame.locals[var] = frame.stack.pop();
				break;
			case Opcodes.LSTORE:
				assert frame.stack.peek().getType().isSubtypeOf(typeFactory.getType(long.class));
				frame.locals[var] = frame.stack.pop();
				break;
			case Opcodes.FSTORE:
				assert frame.stack.peek().getType().isSubtypeOf(typeFactory.getType(float.class));
				frame.locals[var] = frame.stack.pop();
				break;
			case Opcodes.DSTORE:
				assert frame.stack.peek().getType().isSubtypeOf(typeFactory.getType(double.class));
				frame.locals[var] = frame.stack.pop();
				break;
			case Opcodes.ASTORE:
				assert frame.stack.peek().getType() instanceof ReferenceType;
				frame.locals[var] = frame.stack.pop();
				break;
			default:
				throw new UnsupportedOperationException(""+insn.getOpcode());
		}
	}

	private Klass getKlassByInternalName(String internalName) {
		String binaryName = internalName.replace('/', '.');
		Klass k = module.getKlass(binaryName);
		if (k != null)
			return k;

		Class<?> c = null;
		try {
			c = Class.forName(binaryName);
		} catch (ClassNotFoundException ex) {
			Thread.currentThread().stop(ex);
		}
		return module.getKlass(c);
	}

	//<editor-fold defaultstate="collapsed" desc="Stack manipulation opcodes support">
	/**
	 * Conditionally permutes the values on the operand stack in the given
	 * frame. The permutations are given as an array of 2-element arrays, the
	 * first element of which specifies the condition as a constraint on the
	 * categories of the stacked operand types, with the top of the stack
	 * beginning at index 0, and the second element of which specifies 1-based
	 * indices giving the resulting permutation, with the element at index 0
	 * being towards the bottom of the stack (pushed first). (This matches the
	 * instruction descriptions in the JVMS.)
	 *
	 * Strictly speaking, the permutations need not be permutations; they may
	 * contain duplicate or dropped indices.
	 *
	 * Only one permutation will be applied.  If no permutation matches, an
	 * AssertionError is thrown.
	 *
	 * This is used for the implementation of the DUP instruction family.
	 * @param frame the frame containing the stack to permute
	 * @param permutations the conditional permutations to apply
	 */
	private void conditionallyPermute(FrameState frame, int[][][] permutations) {
		for (int[][] permutation : permutations) {
			int[] categories = permutation[0];
			if (Arrays.equals(categories, categoriesOnStack(frame, categories.length))) {
				Value[] v = new Value[categories.length];
				for (int i = 0; i < v.length; ++i)
					v[i] = frame.stack.pop();
				for (int i : permutation[1])
					frame.stack.push(v[i-1]);
				return;
			}
		}
		throw new AssertionError("no permutations matched");
	}

	/**
	 * Returns an array containing the categories of the first n values on the
	 * operand stack of the given frame.  If there are fewer values, the rest
	 * are filled with -1.
	 * @param frame the frame to check categories on stack of
	 * @return an array of categories
	 */
	private int[] categoriesOnStack(FrameState frame, int n) {
		int[] x = new int[n];
		Arrays.fill(x, -1);
		Value[] v = frame.stack.toArray(new Value[0]);
		for (int i = 0; i < v.length && i < x.length; ++i)
			x[i] = categoryOf(v[i].getType());
		return x;
	}
	//</editor-fold>

	private void binary(BinaryInst.Operation operation, FrameState frame, BBInfo block) {
		Value right = frame.stack.pop();
		Value left = frame.stack.pop();
		BinaryInst inst = new BinaryInst(left, operation, right);
		block.block.instructions().add(inst);
		frame.stack.push(inst);
	}

	/**
	 * Merge the given frame state with the entry state of the given block,
	 * RAUW-ing as required if phi instructions are inserted.
	 * @param p the final frame state of a predecessor
	 * @param s the successor block
	 */
	private void merge(FrameState p, BBInfo s) {
		if (s.entryState == null) {
			s.entryState = p.copy();
			return;
		} else
			throw new UnsupportedOperationException("TODO: phi insertion");
	}

	private final class BBInfo {
		private final BasicBlock block;
		//The index of the first and one-past-the-last instructions.
		private final int start, end;
		private FrameState entryState;
		private final FrameNode frame;
		private BBInfo(int start, int end) {
			this.block = new BasicBlock(method.getParent().getParent());
			method.basicBlocks().add(this.block);
			this.start = start;
			this.end = end;
			if (start == 0) { //first block starts with args and empty stack
				this.entryState = new FrameState(methodNode.maxLocals);
				Value[] entryLocals = entryState.locals;
				int i = 0;
				//If the method is a constructor, it begins with an
				//UninitializedThis object in local variable 0.
				if (method.getName().equals("<init>"))
					entryLocals[i++] = uninitializedThis;
				for (Argument a : method.arguments()) {
					entryLocals[i] = a;
					Type argType = a.getType();
					i += categoryOf(argType);
				}
			}
			this.frame = findOnlyFrameNode();
		}

		private FrameNode findOnlyFrameNode() {
			FrameNode f = null;
			for (int i = start; i != end; ++i) {
				AbstractInsnNode insn = methodNode.instructions.get(i);
				if (insn instanceof FrameNode) {
					assert f == null : f + " " +insn;
					f = (FrameNode)insn;
				}
			}
			return f;
		}
	}

	/**
	 * Returns the "category" of the given type, the number of stack or local
	 * slots it occupies in a frame.
	 * @param t a type
	 * @return the type's category
	 */
	private int categoryOf(Type t) {
		return (t.equals(typeFactory.getType(long.class)) || t.equals(typeFactory.getType(double.class))) ? 2 : 1;
	}

	private final class FrameState {
		private final Value[] locals;
		private final Deque<Value> stack;
		private FrameState(int localSize) {
			this.locals = new Value[localSize];
			this.stack = new ArrayDeque<>();
		}
		private FrameState copy() {
			FrameState s = new FrameState(locals.length);
			System.arraycopy(locals, 0, s.locals, 0, locals.length);
			return s;
		}
		private void replace(Value toBeReplaced, Value replacement) {
			for (int i = 0; i < locals.length; ++i)
				if (toBeReplaced.equals(locals[i]))
					locals[i] = replacement;
			//Best we can do with a deque.
			Value[] v = stack.toArray(new Value[0]);
			for (int i = 0; i < v.length; ++i)
				if (toBeReplaced.equals(v[i]))
					v[i] = replacement;
			stack.clear();
			for (Value x : v)
				stack.add(x);
		}
		@Override
		public String toString() {
			return "Locals: "+Arrays.toString(locals)+", Stack: "+stack.toString();
		}
	}

	/**
	 * A dummy value used when building SSA form.  Exists only to get RAUW'd to
	 * the result of the constructor call.
	 *
	 * Has the type of the object under construction.
	 */
	private static class UninitializedValue extends Value {
		private UninitializedValue(Type type, String name) {
			super(type, name);
		}
		@Override
		public String toString() {
			return getName();
		}
	}

	public static void main(String[] args) {
		Module m = new Module();
		Klass k = m.getKlass(MethodResolver.class);
		k.getMethods("buildInstructions").iterator().next().resolve();
	}
}
