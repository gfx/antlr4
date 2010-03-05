package org.antlr.v4.automata;


import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.v4.misc.IntSet;
import org.antlr.v4.parse.GrammarASTAdaptor;
import org.antlr.v4.parse.NFABuilder;
import org.antlr.v4.tool.*;

import java.lang.reflect.Constructor;
import java.util.List;

/** NFA construction routines triggered by NFABuilder.g.
 *
 *  No side-effects. It builds an NFA object and returns it.
 */
public class ParserNFAFactory implements NFAFactory {
	public Grammar g;
	public Rule currentRule;
	NFA nfa;

	public ParserNFAFactory(Grammar g) { this.g = g; nfa = new NFA(g); }

	public NFA createNFA() {
		createRuleStartAndStopNFAStates();

		GrammarASTAdaptor adaptor = new GrammarASTAdaptor();
		for (Rule r : g.rules.values()) {
			CommonTreeNodeStream nodes = new CommonTreeNodeStream(adaptor,r.ast);
			NFABuilder b = new NFABuilder(nodes,this);
			try { b.rule();	}
			catch (RecognitionException re) {
				ErrorManager.fatalInternalError("bad grammar AST structure", re);
			}
		}

		addEOFTransitionToStartRules();
		return nfa;
	}

	public void setCurrentRuleName(String name) {
		this.currentRule = g.getRule(name);
	}

	/* start->ruleblock->end */
	public Handle rule(GrammarAST ruleAST, String name, Handle blk) {
		Rule r = g.getRule(name);
		RuleStartState start = nfa.ruleToStartState.get(r);
		epsilon(start, blk.left);
		RuleStopState stop = nfa.ruleToStopState.get(r);
		epsilon(blk.right, stop);
		Handle h = new Handle(start, stop);
		FASerializer ser = new FASerializer(g, h.left);
		System.out.println(ruleAST.toStringTree()+":\n"+ser);
		return h;
	}

	public NFAState newState(Class nodeType, GrammarAST node) {
		try {
			Constructor ctor = nodeType.getConstructor(NFA.class);
			NFAState s = (NFAState)ctor.newInstance(nfa);
			s.ast = node;
			nfa.addState(s);
			return s;
		}
		catch (Exception e) {
			ErrorManager.internalError("can't create NFA node: "+nodeType.getName(), e);
		}
		return null;
	}

	public BasicState newState(GrammarAST node) {
		BasicState n = new BasicState(nfa);
		n.ast = node;
		nfa.addState(n);
		return n;
	}

	public BasicState newState() { return newState(null); }

	/** From label A build Graph o-A->o */
	public Handle tokenRef(TerminalAST node) {
		BasicState left = newState(node);
		BasicState right = newState(node);
		int ttype = g.getTokenType(node.getText());
		left.transition = new AtomTransition(ttype, right);
		return new Handle(left, right);
	}

	/** From set build single edge graph o->o-set->o.  To conform to
     *  what an alt block looks like, must have extra state on left.
     */
	public Handle set(IntSet set, GrammarAST associatedAST) { return null; }

	public Handle tree(List<Handle> els) {
		return null;
	}

	public Handle range(GrammarAST a, GrammarAST b) { return null; }

	public Handle not(Handle A) {
		return null;
	}

	/** From char 'c' build o-intValue(c)->o
	 */
	public Handle charLiteral(GrammarAST charLiteralAST) { return null; }

	/** For a non-lexer, just build a simple token reference atom.
	 *  For a lexer, a string is a sequence of char to match.  That is,
	 *  "fog" is treated as 'f' 'o' 'g' not as a single transition in
	 *  the DFA.  Machine== o-'f'->o-'o'->o-'g'->o and has n+1 states
	 *  for n characters.
	 */
	public Handle stringLiteral(TerminalAST stringLiteralAST) {
		return tokenRef(stringLiteralAST);
	}

	/** For reference to rule r, build
	 *
	 *  o->(r)  o
	 *
	 *  where (r) is the start of rule r and the trailing o is not linked
	 *  to from rule ref state directly (uses followState).
	 */
	public Handle ruleRef(GrammarAST node) {
		Rule r = g.getRule(node.getText());
		RuleStartState start = nfa.ruleToStartState.get(r);
		RuleStartState stop = nfa.ruleToStartState.get(r);
		BasicState left = newState(node);
		BasicState right = newState(node);
		RuleTransition call = new RuleTransition(r, start, stop);
		call.followState = right;
		left.transition = call;
		return new Handle(left, right);
	}

	/** From an empty alternative build  o-e->o */
	public Handle epsilon(GrammarAST node) {
		BasicState left = newState(node);
		BasicState right = newState(node);
		epsilon(left, right);
		return new Handle(left, right);
	}

	/** Build what amounts to an epsilon transition with a semantic
	 *  predicate action.  The pred is a pointer into the AST of
	 *  the SEMPRED token.
	 */
	public Handle sempred(GrammarAST pred) {
		System.out.println("sempred: "+ pred);
		BasicState left = newState(pred);
		NFAState right = newState(pred);
		left.transition = new PredicateTransition(pred, right);
		return new Handle(left, right);
	}

	public Handle gated_sempred(GrammarAST pred) {
		return null;
	}

	/** Build what amounts to an epsilon transition with an action.
	 *  The action goes into NFA though it is ignored during analysis.
	 *  It slows things down a bit, but I must ignore predicates after
	 *  having seen an action (5-5-2008).
	 */
	public Handle action(GrammarAST action) {
		System.out.println("action: "+action);
		BasicState left = newState(action);
		NFAState right = newState(action);
		left.transition = new ActionTransition(action, right);  
		return new Handle(left, right);
	}

	/** From a set ('a'|'b') build
     *
     *  o->o-'a'..'b'->o->o (last NFAState is blockEndNFAState pointed to by all alts)
	 */
	public Handle blockFromSet(Handle set) { return null; }

	/** From A|B|..|Z alternative block build
     *
     *  o->o-A->o->o (last NFAState is BlockEndState pointed to by all alts)
     *  |          ^
     *  |->o-B->o--|
     *  |          |
     *  ...        |
     *  |          |
     *  |->o-Z->o--|
     *
     *  So start node points at every alternative with epsilon transition
     *  and every alt right side points at a block end NFAState.
     *
     *  Special case: only one alternative: don't make a block with alt
     *  begin/end.
     *
     *  Special case: if just a list of tokens/chars/sets, then collapse
     *  to a single edge'd o-set->o graph.
     *
     *  TODO: Set alt number (1..n) in the states?
     */
	public Handle block(GrammarAST blkAST, List<Handle> alts) {
		if ( alts.size()==1 ) return alts.get(0);
				
		BlockStartState start = (BlockStartState)newState(BlockStartState.class, blkAST);
		BlockEndState end = (BlockEndState)newState(BlockEndState.class, blkAST);
		for (Handle alt : alts) {
			epsilon(start, alt.left);
			epsilon(alt.right, end);
		}
		Handle h = new Handle(start, end);
		FASerializer ser = new FASerializer(g, h.left);
		nfa.defineDecisionState(start);
		System.out.println(blkAST.toStringTree()+":\n"+ser);
		return h;
	}

	public Handle alt(List<Handle> els) {
		Handle prev = null;
		for (Handle el : els) { // hook up elements
			if ( prev!=null ) epsilon(prev.right, el.left);
			prev = el;
		}
		Handle first = els.get(0);
		Handle last = els.get(els.size()-1);
		return new Handle(first.left, last.right);
	}

	/** From (A)? build either:
	 *
	 *  o--A->o
	 *  |     ^
	 *  o---->|
	 *
	 *  or, if A is a block, just add an empty alt to the end of the block
	 */
	public Handle optional(GrammarAST optAST, Handle blk) {
		if ( blk.left instanceof BlockStartState ) {
			epsilon(blk.left, blk.right);
			FASerializer ser = new FASerializer(g, blk.left);
			System.out.println(optAST.toStringTree()+":\n"+ser);
			return blk;
		}

		// construct block
		BlockStartState start = (BlockStartState)newState(BlockStartState.class, optAST);
		BlockEndState end = (BlockEndState)newState(BlockEndState.class, optAST);
		epsilon(start, blk.left);
		epsilon(blk.right, end);
		epsilon(start, end);

		nfa.defineDecisionState(start);

		Handle h = new Handle(start, end);
		FASerializer ser = new FASerializer(g, h.left);
		System.out.println(optAST.toStringTree()+":\n"+ser);
		return h;
	}

	/** From (A)+ build
	 *
	 *     |------|
	 *     v      |
	 *  o->o-A-o->o->o
	 *
	 *  Meaning that the last NFAState in A blk points to loop back node,
	 *  which points back to block start.  We add start/end nodes to
	 *  outside.
	 */
	public Handle plus(GrammarAST plusAST, Handle blk) {
		PlusBlockStartState start = (PlusBlockStartState)newState(PlusBlockStartState.class, plusAST);
		LoopbackState loop = (LoopbackState)newState(LoopbackState.class, plusAST);
		BlockEndState end = (BlockEndState)newState(BlockEndState.class, plusAST);
		epsilon(start, blk.left);
		epsilon(loop, blk.left);
		epsilon(blk.right, loop);
		epsilon(loop, end);
		return new Handle(start, end);
	}

	/** From (A)* build
	 *
	 *     |---|
	 *     v   |
	 *  o->o-A-o--o (Transition 2 from block end points at alt 1; follow is Transition 1)
	 *  |         ^
	 *  o---------| (optional branch is 2nd alt of optional block containing A+)
	 *
	 *  Meaning that the last (end) NFAState in A points back to A's
	 *  left side NFAState and we add 3 new NFAStates (the
	 *  optional branch is built just like an optional subrule).
	 *  See the Aplus() method for more on the loop back Transition.
	 *  The new node on right edge is set to RIGHT_EDGE_OF_CLOSURE so we
	 *  can detect nested (A*)* loops and insert an extra node.  Previously,
	 *  two blocks shared same EOB node.
	 *
	 *  There are 2 or 3 decision points in a A*.  If A is not a block (i.e.,
	 *  it only has one alt), then there are two decisions: the optional bypass
	 *  and then loopback.  If A is a block of alts, then there are three
	 *  decisions: bypass, loopback, and A's decision point.
	 *
	 *  Note that the optional bypass must be outside the loop as (A|B)* is
	 *  not the same thing as (A|B|)+.
	 *
	 *  This is an accurate NFA representation of the meaning of (A)*, but
	 *  for generating code, I don't need a DFA for the optional branch by
	 *  virtue of how I generate code.  The exit-loopback-branch decision
	 *  is sufficient to let me make an appropriate enter, exit, loop
	 *  determination.  See codegen.g
	 */
	public Handle star(GrammarAST starAST, Handle blk) { return null; }

	/** Build an atom with all possible values in its label */
	public Handle wildcard(GrammarAST associatedAST) { return null; }

	/** Build a subrule matching ^(. .*) (any tree or node). Let's use
	 *  (^(. .+) | .) to be safe.
	 */
	public Handle wildcardTree(GrammarAST associatedAST) { return null; }

	void epsilon(NFAState a, NFAState b) {
		a.addTransition(new EpsilonTransition(b));
	}

	/** Define all the rule begin/end NFAStates to solve forward reference
	 *  issues.
	 */
	void createRuleStartAndStopNFAStates() {
		for (Rule r : g.rules.values()) {
			RuleStartState start = (RuleStartState)newState(RuleStartState.class, r.ast);
			RuleStopState stop = (RuleStopState)newState(RuleStopState.class, r.ast);
			start.stopState = stop;
			start.rule = r;
			stop.rule = r;
			nfa.ruleToStartState.put(r, start);
			nfa.ruleToStopState.put(r, stop);
		}
	}

	/** add an EOF transition to any rule end NFAState that points to nothing
     *  (i.e., for all those rules not invoked by another rule).  These
     *  are start symbols then.
	 *
	 *  Return the number of grammar entry points; i.e., how many rules are
	 *  not invoked by another rule (they can only be invoked from outside).
	 *  These are the start rules.
     */
	public int addEOFTransitionToStartRules() {
		int n = 0;
		for (Rule r : g.rules.values()) {
			NFAState stop = nfa.ruleToStopState.get(r);
			if ( stop.getNumberOfTransitions()==0 ) {
				n++;
				continue;
			}
			BasicState eofTarget = newState(r.ast);
			Transition t = new AtomTransition(Label.EOF, eofTarget);
			stop.addTransition(t);
		}
		return n;
	}
}