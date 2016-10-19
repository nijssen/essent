package essent

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes._
import firrtl.Utils._


object WireConstProp extends Pass {
  def name = "Constant Propagation (replace constant wires)"

  def findLiteralWires(s: Statement): Seq[(String,Literal)] = s match {
    case b: Block => b.stmts flatMap findLiteralWires
    case Connect(_, w: WRef, l: Literal) => Seq((w.name, l))
    case _ => Seq()
  }

  def replaceLiteralWiresStmt(consts: Map[String,Literal])(s: Statement): Statement = {
    val noConstConnects = s match {
      case Connect(_, w: WRef, l: Literal) => EmptyStmt
      case _ => s
    }
    noConstConnects map replaceLiteralWiresStmt(consts) map replaceLiteralWiresExpr(consts)
  }

  def replaceLiteralWiresExpr(consts: Map[String,Literal])(e: Expression): Expression = {
    val replaced = e match {
      case w: WRef => if (consts contains w.name) consts(w.name) else w
      case _ => e
    }
    replaced map replaceLiteralWiresExpr(consts)
  }

  def constPropModule(m: Module): Module = {
    val constWires = findLiteralWires(m.body).toMap
    val replacedStmts = replaceLiteralWiresStmt(constWires)(m.body)
    Module(m.info, m.name, m.ports, squashEmpty(replacedStmts))
  }

  def run(c: Circuit): Circuit = {
    val modulesx = c.modules.map {
      case m: ExtModule => m
      case m: Module => constPropModule(m)
    }
    Circuit(c.info, modulesx, c.main)
  }
}
