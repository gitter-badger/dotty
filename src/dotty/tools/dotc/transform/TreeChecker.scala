package dotty.tools.dotc
package transform

import TreeTransforms._
import core.DenotTransformers._
import core.Denotations._
import core.SymDenotations._
import core.Contexts._
import core.Symbols._
import core.Types._
import core.Flags._
import core.Constants._
import core.StdNames._
import core.Decorators._
import core.TypeErasure.isErasedType
import core.Phases.Phase
import typer._
import typer.ErrorReporting._
import reporting.ThrowingReporter
import ast.Trees._
import ast.{tpd, untpd}
import util.SourcePosition
import collection.mutable
import ProtoTypes._
import java.lang.AssertionError

/** Run by -Ycheck option after a given phase, this class retypes all syntax trees
 *  and verifies that the type of each tree node so obtained conforms to the type found in the tree node.
 *  It also performs the following checks:
 *
 *   - The owner of each definition is the same as the owner of the current typing context.
 *   - Ident nodes do not refer to a denotation that would need a select to be accessible
 *     (see tpd.needsSelect).
 *   - After typer, identifiers and select nodes refer to terms only (all types should be
 *     represented as TypeTrees then).
 */
class TreeChecker {
  import ast.tpd._

  private def previousPhases(phases: List[Phase])(implicit ctx: Context): List[Phase] = phases match {
    case (phase: TreeTransformer) :: phases1 =>
      val subPhases = phase.transformations.map(_.phase)
      val previousSubPhases = previousPhases(subPhases.toList)
      if (previousSubPhases.length == subPhases.length) previousSubPhases ::: previousPhases(phases1)
      else previousSubPhases
    case phase :: phases1 if phase ne ctx.phase =>
      phase :: previousPhases(phases1)
    case _ =>
      Nil
  }

  def check(phasesToRun: Seq[Phase], ctx: Context) = {
    val prevPhase = ctx.phase.prev // can be a mini-phase
    val squahsedPhase = ctx.squashed(prevPhase)
    println(s"checking ${ctx.compilationUnit} after phase ${squahsedPhase}")
    val checkingCtx = ctx.fresh
      .setTyperState(ctx.typerState.withReporter(new ThrowingReporter(ctx.typerState.reporter)))
    val checker = new Checker(previousPhases(phasesToRun.toList)(ctx))
    try checker.typedExpr(ctx.compilationUnit.tpdTree)(checkingCtx)
    catch {
      case ex: Throwable =>
        implicit val ctx: Context = checkingCtx
        println(i"*** error while checking after phase ${checkingCtx.phase.prev} ***")
        throw ex
    }
  }

  class Checker(phasesToCheck: Seq[Phase]) extends ReTyper {

    val definedSyms = new mutable.HashSet[Symbol]

    def withDefinedSym[T](tree: untpd.Tree)(op: => T)(implicit ctx: Context): T = {
      if (tree.isDef) {
        assert(!definedSyms.contains(tree.symbol), i"doubly defined symbol: ${tree.symbol}in $tree")
        definedSyms += tree.symbol
        //println(i"defined: ${tree.symbol}")
        val res = op
        definedSyms -= tree.symbol
        //println(i"undefined: ${tree.symbol}")
        res
      }
      else op
    }

    def withDefinedSyms[T](trees: List[untpd.Tree])(op: => T)(implicit ctx: Context) =
      trees.foldRightBN(op)(withDefinedSym(_)(_))

    def withDefinedSymss[T](vparamss: List[List[untpd.ValDef]])(op: => T)(implicit ctx: Context): T =
      vparamss.foldRightBN(op)(withDefinedSyms(_)(_))

    def assertDefined(tree: untpd.Tree)(implicit ctx: Context) =
      if (tree.symbol.maybeOwner.isTerm)
        assert(definedSyms contains tree.symbol, i"undefined symbol ${tree.symbol}")

    override def typedUnadapted(tree: untpd.Tree, pt: Type)(implicit ctx: Context): tpd.Tree = {
      val res = tree match {
        case _: untpd.UnApply =>
          // can't recheck patterns
          tree.asInstanceOf[tpd.Tree]
        case _: untpd.TypedSplice | _: untpd.Thicket | _: EmptyValDef[_] =>
          super.typedUnadapted(tree)
        case _ if tree.isType =>
          promote(tree)
        case _ =>
          val tree1 = super.typedUnadapted(tree, pt)
          def isSubType(tp1: Type, tp2: Type) =
            (tp1 eq tp2) || // accept NoType / NoType
            (tp1 <:< tp2)
          def divergenceMsg(tp1: Type, tp2: Type) =
            s"""Types differ
               |Original type : ${tree.typeOpt.show}
               |After checking: ${tree1.tpe.show}
               |Original tree : ${tree.show}
               |After checking: ${tree1.show}
               |Why different :
             """.stripMargin + core.TypeComparer.explained((tp1 <:< tp2)(_))
          if (tree.hasType) // it might not be typed because Typer sometimes constructs new untyped trees and resubmits them to typedUnadapted
            assert(isSubType(tree1.tpe, tree.typeOpt), divergenceMsg(tree1.tpe, tree.typeOpt))
          tree1
      }
      phasesToCheck.foreach(_.checkPostCondition(res))
      res
    }

    override def typedIdent(tree: untpd.Ident, pt: Type)(implicit ctx: Context): Tree = {
      assert(tree.isTerm || !ctx.isAfterTyper, tree.show + " at " + ctx.phase)
      assert(tree.isType || !needsSelect(tree.tpe), i"bad type ${tree.tpe} for $tree # ${tree.uniqueId}")
      assertDefined(tree)
      super.typedIdent(tree, pt)
    }

    override def typedSelect(tree: untpd.Select, pt: Type)(implicit ctx: Context): Tree = {
      assert(tree.isTerm || !ctx.isAfterTyper, tree.show + " at " + ctx.phase)
      super.typedSelect(tree, pt)
    }

    private def checkOwner(tree: untpd.Tree)(implicit ctx: Context): Unit = {
      def ownerMatches(symOwner: Symbol, ctxOwner: Symbol): Boolean =
        symOwner == ctxOwner ||
        ctxOwner.isWeakOwner && ownerMatches(symOwner, ctxOwner.owner)
      assert(ownerMatches(tree.symbol.owner, ctx.owner),
        i"bad owner; ${tree.symbol} has owner ${tree.symbol.owner}, expected was ${ctx.owner}\n" +
        i"owner chain = ${tree.symbol.ownersIterator.toList}%, %, ctxOwners = ${ctx.outersIterator.map(_.owner).toList}%, %")
    }

    override def typedClassDef(cdef: untpd.TypeDef, cls: ClassSymbol)(implicit ctx: Context) = {
      val TypeDef(_, _, impl @ Template(constr, _, _, _)) = cdef
      assert(cdef.symbol == cls)
      assert(impl.symbol.owner == cls)
      assert(constr.symbol.owner == cls)
      assert(cls.primaryConstructor == constr.symbol, i"mismatch, primary constructor ${cls.primaryConstructor}, in tree = ${constr.symbol}")
      checkOwner(impl)
      checkOwner(impl.constr)
      super.typedClassDef(cdef, cls)
    }

    override def typedDefDef(ddef: untpd.DefDef, sym: Symbol)(implicit ctx: Context) =
      withDefinedSyms(ddef.tparams) {
        withDefinedSymss(ddef.vparamss) {
          super.typedDefDef(ddef, sym)
        }
      }

    override def typedCase(tree: untpd.CaseDef, pt: Type, selType: Type, gadtSyms: Set[Symbol])(implicit ctx: Context): CaseDef = {
      withDefinedSyms(tree.pat.asInstanceOf[tpd.Tree].filterSubTrees(_.isInstanceOf[ast.Trees.Bind[_]])) {
        super.typedCase(tree, pt, selType, gadtSyms)
      }
    }

    override def typedBlock(tree: untpd.Block, pt: Type)(implicit ctx: Context) =
      withDefinedSyms(tree.stats) { super.typedBlock(tree, pt) }

    /** Check that all defined symbols have legal owners.
     *  An owner is legal if it is either the same as the context's owner
     *  or there's an owner chain of valdefs starting at the context's owner and
     *  reaching up to the symbol's owner. The reason for this relaxed matching
     *  is that we should be able to pull out an expression as an initializer
     *  of a helper value without having to do a change owner traversal of the expression.
     */
    override def typedStats(trees: List[untpd.Tree], exprOwner: Symbol)(implicit ctx: Context): List[Tree] = {
      for (tree <- trees) tree match {
        case tree: untpd.DefTree => checkOwner(tree)
        case _: untpd.Thicket => assert(false, i"unexpanded thicket $tree in statement sequence $trees%\n%")
        case _ =>
      }
      super.typedStats(trees, exprOwner)
    }

    override def ensureNoLocalRefs(block: Block, pt: Type, forcedDefined: Boolean = false)(implicit ctx: Context): Tree =
      block

    override def adapt(tree: Tree, pt: Type, original: untpd.Tree = untpd.EmptyTree)(implicit ctx: Context) = {
      def isPrimaryConstructorReturn =
        ctx.owner.isPrimaryConstructor && pt.isRef(ctx.owner.owner) && tree.tpe.isRef(defn.UnitClass)
      if (ctx.mode.isExpr &&
          !tree.isEmpty &&
          !isPrimaryConstructorReturn &&
          !pt.isInstanceOf[FunProto])
        assert(tree.tpe <:< pt,
            s"error at ${sourcePos(tree.pos)}\n" +
            err.typeMismatchStr(tree.tpe, pt) + "\ntree = " + tree)
      tree
    }
  }
}

object TreeChecker extends TreeChecker