package com.nicta.scoobi
package impl
package plan
package smart

import testing.mutable.UnitSpecification
import io.ConstantStringDataSource
import org.kiama.rewriting._
import core.{Emitter, BasicDoFn}
import org.specs2.matcher.DataTables
import org.specs2.ScalaCheck
import org.scalacheck.{Prop, Arbitrary, Gen}
import Rewriter._
import org.kiama.output.PrettyPrinter
import data.Data

class OptimisationsSpec extends UnitSpecification with Optimiser with DataTables with DCompData with ScalaCheck {
  // here 'size' is the depth of the graph
  override def defaultValues = Map(minTestsOk->1000, maxDiscarded ->500, minSize->1, maxSize->8, workers->1)

  "1. Nodes must be flattened" >> {
    "1.1 A Flatten Node which is an input to 2 other nodes must be copied to each node" >> {
      optimise(flattenSplit, parallelDo(f1), parallelDo(f1)) must beLike {
        case ParallelDo(ff1,_,_,_,_) :: ParallelDo(ff2,_,_,_,_) :: _  => nodesAreDistinct(ff1, ff2)
      }
      optimise(flattenSplit, parallelDo(f1), parallelDo(f1), parallelDo(f1)) must beLike {
        case ParallelDo(ff1,_,_,_,_) :: ParallelDo(ff2,_,_,_,_)  :: ParallelDo(ff3,_,_,_,_) :: _  => nodesAreDistinct(ff1, ff2, ff3)
      }
    }
    "1.2 A Flatten Node which is an input to a ParallelDo then replicate the ParallelDo on each of the Flatten inputs" >> {
      optimise(flattenSink, parallelDo(flattens)) must beLike {
        case List(Flatten(ParallelDo(ll1,_,_,_,_) :: ParallelDo(ll2,_,_,_,_) :: _))  => (l1 === ll1) and (l2 === ll2)
      }
    }
    "1.3 A Flatten Node with Flatten inputs must collect all the inner inputs" >> {
      "input"                                       | "expected"                                    |>
      flatten(l1)                                   ! flatten(l1)                                   |
      flatten(flatten(l1))                          ! flatten(l1)                                   |
      flatten(flatten(flatten(l1)))                 ! flatten(l1)                                   |
      flatten(flatten(l1), l1)                      ! flatten(l1, l1)                               |
      flatten(l1, flatten(l1))                      ! flatten(l1, l1)                               |
      flatten(l1, flatten(l1), l2)                  ! flatten(l1, l1, l2)                           |
      flatten(l1, l2, flatten(flatten(l1)))         ! flatten(l1, l2, l1)                           |
      flatten(l1, l2, flatten(l1, pd(rt), l1), l2)  ! flatten(l1, l2, l1, pd(rt), l1, l2)           | { (input, output) =>
        showStructure(optimise(flattenFuse, input).head) === showStructure(output)
      }

      check(Prop.forAll { (node: AstNode) =>
        collectNestedFlatten(optimise(flattenFuse, node).head) aka show(node) must beEmpty
      })
    }
  }

  "2. Combines must be optimised" >> {
    "A Combine which doesn't have a GroupByKey as an Input must be transformed to a ParallelDo" >> {
      "input"                                        | "expected"                                    |
       cb(l1)                                        ! pd(l1)                                        |
       cb(gbk(l1))                                   ! cb(gbk(l1))                                   |> { (input, output) =>
         showStructure(optimise(combineToParDo, input).head) === showStructure(output)
       }
    }
    "Any optimised Combine in the graph can only have GroupByKey as an input" >> check { (node: AstNode) =>
      forall(collectCombine(optimise(combineToParDo, node).head)) { n =>
        n aka show(node) must beLike { case Combine(GroupByKey(_), _) => ok }
      }
    }
    "After optimisation, all the transformed Combines must be ParallelDo" >> check { (node: AstNode) =>
      val optimised = optimise(combineToParDo, node).head
      (collectCombine(node).size + collectParallelDo(node).size) ===
      (collectCombineGbk(optimised).size + collectParallelDo(optimised).size)
    }
  }

  "3. Successive ParallelDos must be fused" >> check { (node: AstNode) =>
    val optimised = optimise(parDoFuse, node).head
    collectSuccessiveParDos(optimised) must beEmpty
  };p

  "4. GroupByKeys" >> {
    "4.1 the GroupByKey is replicated so that it can not be the input of different nodes  " >> check { (node: AstNode) =>
      val optimised = optimise(groupByKeySplit, node).head

      // collects the gbks, they must form a set and not a bag
      val original = collectGroupByKey(node).map(_.id)
      val gbks = collectGroupByKey(optimised).map(_.id)
      original.size aka show(node) must be_>=(gbks.size)
      gbks.size aka optimisation(node, optimised) must_== gbks.toSet.size
    }

    "4.2 if the input of a GroupByKey is a Flatten, the Flatten is also replicated" >> check { (node: AstNode) =>
      val optimised = optimise(groupByKeySplit, node).head

      // collects the flattens inside GroupByKey, they must form a set and not a bag
      val flattens = collectGBKFlatten(optimised).map(_.id)
      flattens.size aka optimisation(node, optimised) must_== flattens.toSet.size
    }

    "4.3 examples" >> {
      optimise(groupByKeySplit, parallelDo(gbk1), parallelDo(gbk1)) must beLike {
        case ParallelDo(ggbk1,_,_,_,_) :: ParallelDo(ggbk2,_,_,_,_) :: _  => nodesAreDistinct(ggbk1, ggbk2)
      }
      optimise(groupByKeySplit, parallelDo(gbk1), parallelDo(gbk1), parallelDo(gbk1)) must beLike {
        case ParallelDo(ggbk1,_,_,_,_) :: ParallelDo(ggbk2,_,_,_,_)  :: ParallelDo(ggbk3,_,_,_,_) :: _  => nodesAreDistinct(ggbk1, ggbk2, ggbk3)
      }
      optimise(groupByKeySplit, flatten(gbkf1), flatten(gbkf1), flatten(gbkf1)) must beLike {
        case Flatten((ggbk1 @ GroupByKey(ff1))::_) :: Flatten((ggbk2 @ GroupByKey(ff2))::_)  :: Flatten((ggbk3 @ GroupByKey(ff3))::_) :: _  =>
          nodesAreDistinct(ggbk1, ggbk2, ggbk3) and nodesAreDistinct(ff1, ff2, ff3)
      }
    }
  }

  val (l1, l2) = (load, load)
  val f1       = flatten(l1)
  val flattens = flatten(l1, l2)
  val gbk1     = gbk(l1)
  val gbkf1    = gbk(f1)

  def collectNestedFlatten = collectl {
    case f @ Flatten(ins) if ins exists isFlatten => f
  }

  def nodesAreDistinct(nodes: AstNode*) = nodes.map(_.id).distinct.size === nodes.size

  def collectFlatten          = collectl { case f @ Flatten(_) => f }
  def collectCombine          = collectl { case c @ Combine(_,_) => c }
  def collectCombineGbk       = collectl { case c @ Combine(GroupByKey(_),_) => c }
  def collectParallelDo       = collectl { case p @ ParallelDo(_,_,_,_,_) => p }
  def collectSuccessiveParDos = collectl { case p @ ParallelDo(ParallelDo(_,_,_,_,_),_,_,_,false) => p }
  def collectGroupByKey       = collectl { case g @ GroupByKey(_) => g }
  def collectGBKFlatten       = collectl { case GroupByKey(f @ Flatten(_)) => f }
}

trait Optimiser {
  type Term = AstNode

  def flattenSplit = everywhere(rule {
    case f @ Flatten(_) => f.clone
  })

  def flattenSink = everywhere(rule {
    case p @ ParallelDo(Flatten(ins),_,_,_,_) => Flatten(ins.map(in => p.copy(in)))
  })

  val isFlatten: DComp[_,_] => Boolean = { case Flatten(_) => true; case other => false }

  def flattenFuse = repeat(sometd(rule {
    case Flatten(ins) if ins exists isFlatten => Flatten(ins.flatMap { case Flatten(nodes) => nodes; case other => List(other) })
  }))

  def combineToParDo = everywhere(rule {
    case c @ Combine(GroupByKey(_), _) => c
    case c @ Combine(other, f)         => c.toParallelDo
  })

  def parDoFuse = repeat(sometd(rule {
    case p1 @ ParallelDo(p2 @ ParallelDo(_,_,_,_,_),_,_,_,false) => p2 fuse p1
  }))

  def groupByKeySplit = everywhere(rule {
    case g @ GroupByKey(f @ Flatten(ins)) => g.copy(in = f.copy())
    case g @ GroupByKey(_)                => g.clone
  })

  def optimise(strategy: Strategy, nodes: AstNode*): List[AstNode] = {
    rewrite(strategy)(nodes).toList
  }

}

trait DCompData extends Data {

  def load = Load(ConstantStringDataSource("start"))
  def flatten[A](nodes: AstNode*) = Flatten(nodes.toList.map(_.asInstanceOf[DComp[A,Arr]]))
  def parallelDo(in: AstNode) = pd(in)
  def rt = Return("")
  def pd(in: AstNode) = ParallelDo[String, String, Unit](in.asInstanceOf[DComp[String,Arr]], Return(()), fn)
  def cb(in: AstNode) = Combine[String, String](in.asInstanceOf[DComp[(String, Iterable[String]),Arr]], (s1: String, s2: String) => s1 + s2)
  def gbk(in: AstNode) = GroupByKey(in.asInstanceOf[DComp[(String,String),Arr]])
  def mt(in: AstNode) = Materialize(in.asInstanceOf[DComp[String,Arr]])
  def op[A, B](in1: AstNode, in2: AstNode) = Op[A, B, A](in1.asInstanceOf[DComp[A,Exp]], in2.asInstanceOf[DComp[B,Exp]], (a, b) => a)

  lazy val fn = new BasicDoFn[String, String] { def process(input: String, emitter: Emitter[String]) { emitter.emit(input) } }

  lazy val showNode = (_:AstNode).toString
  lazy val showStructure = (n: AstNode) => show(n).replaceAll("\\d", "")
  def optimisation(node: AstNode, optimised: AstNode) =
    if (show(node) != show(optimised)) "INITIAL: \n"+show(node)+"\nOPTIMISED:\n"+show(optimised) else "no optimisation"

  lazy val show = {
    AstNodePrettyPrinter.pretty(_:AstNode): String
  }

  object AstNodePrettyPrinter extends PrettyPrinter {
    def pretty(node : AstNode) = super.pretty(show(node))

    def show(node : AstNode): Doc =
      node match {
        case Load(_)                => value(showNode(node))
        case Flatten(ins)           => showNode(node) <> braces (nest (line <> "+" <> ssep (ins map show, line <> "+")) <> line)
        case ParallelDo(in,_,_,_,_) => showNode(node) <> braces (nest (line <> show(in) <> line))
        case Return(_)              => value(showNode(node))
        case Combine(in,_)          => showNode(node) <> braces (nest (line <> show(in) <> line))
        case GroupByKey(in)         => showNode(node) <> braces (nest (line <> show(in) <> line))
        case Materialize(in)        => showNode(node) <> braces (nest (line <> show(in) <> line))
        case Op(in1, in2, _)        => showNode(node) <> braces (nest (line <> "1." <> show(in1) <> line <> "2." <> show(in2)))
      }
  }


  import Gen._

  def memo[T](g: Gen[T], ratio: Int = 30): Gen[T] = {
    lazy val previousValues = new scala.collection.mutable.HashSet[T]
    def memoizeValue(v: T) = { previousValues.add(v); v }
    for {
      v           <- g
      usePrevious <- choose(0, 100)
      n           <- choose(0, previousValues.size)
    } yield {
      if (usePrevious <= ratio && previousValues.nonEmpty)
        if (n == previousValues.size) previousValues.toList(n - 1) else previousValues.toList(n)
      else memoizeValue(v)
    }
  }

  import scalaz.Scalaz._

  implicit lazy val arbitraryDComp: Arbitrary[AstNode] = Arbitrary(sized(depth => genDComp(depth)))

  import Gen._
  def genDComp(depth: Int = 1): Gen[AstNode] = lzy(Gen.frequency((3, genLoad(depth)),
                                                                 (4, genParallelDo(depth)),
                                                                 (4, genGroupByKey(depth)),
                                                                 (3, genMaterialize(depth)),
                                                                 (3, genCombine(depth)),
                                                                 (5, genFlatten(depth)),
                                                                 (2, genOp(depth)),
                                                                 (2, genReturn(depth))))


  def genLoad       (depth: Int = 1) = oneOf(load, load)
  def genReturn     (depth: Int = 1) = oneOf(rt, rt)
  def genParallelDo (depth: Int = 1) = if (depth <= 1) value(parallelDo(load)) else memo(genDComp(depth - 1) map (parallelDo _))
  def genFlatten    (depth: Int = 1) = if (depth <= 1) value(flatten(load)   ) else memo(choose(1, 3).flatMap(n => listOfN(n, genDComp(depth - 1))).map(l => flatten(l:_*)))
  def genCombine    (depth: Int = 1) = if (depth <= 1) value(cb(load)        ) else memo(genDComp(depth - 1) map (cb _))
  def genOp         (depth: Int = 1) = if (depth <= 1) value(op(load, load)  ) else memo((genDComp(depth - 1) |@| genDComp(depth - 1))((op _)))
  def genMaterialize(depth: Int = 1) = if (depth <= 1) value(mt(load)        ) else memo(genDComp(depth - 1) map (mt _))
  def genGroupByKey (depth: Int = 1) = if (depth <= 1) value(gbk(load)       ) else memo(genDComp(depth - 1) map (gbk _))

}