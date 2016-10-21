package essent

import collection.mutable.HashMap
import java.io.Writer

import firrtl._
import firrtl.Annotations._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.bitWidth
import firrtl.PrimOps._
import firrtl.Utils._


class EmitCpp(writer: Writer) extends Transform {
  val tabs = "  "

  def genCppType(tpe: Type) = tpe match {
    case UIntType(IntWidth(w)) => {
      if (w <= 64) "uint64_t"
      else throw new Exception(s"UInt too wide!")
    }
    case SIntType(IntWidth(w)) => {
      if (w <= 64) "int64_t"
      else throw new Exception(s"SInt too wide!")
    }
    case _ => throw new Exception(s"No CPP type implemented for $tpe")
  }

  def genMask(tpe: Type): String = tpe match {
    case gt: GroundType => {
      val width = gt.width match { case IntWidth(w) => w }
      val maskValue = BigInt((1 << width.toInt) - 1)
      s"0x${maskValue.toString(16)}"
    }
  }

  // assumption: registers can only appear in blocks since whens expanded
  def findRegisters(s: Statement): Seq[DefRegister] = s match {
    case b: Block => b.stmts flatMap findRegisters
    case d: DefRegister => Seq(d)
    case _ => Seq()
  }

  def findWires(s: Statement): Seq[DefWire] = s match {
    case b: Block => b.stmts flatMap findWires
    case d: DefWire => Seq(d)
    case _ => Seq()
  }

  def findMemory(s: Statement): Seq[DefMemory] = s match {
    case b: Block => b.stmts flatMap findMemory
    case d: DefMemory => Seq(d)
    case _ => Seq()
  }

  def processPort(p: Port): Seq[String] = p.tpe match {
    case ClockType => Seq()
    case _ => Seq(genCppType(p.tpe) + " " + p.name + ";")
  }

  def processExpr(e: Expression): String = e match {
    case w: WRef => w.name
    case u: UIntLiteral => "0x" + u.value.toString(16)
    case m: Mux => {
      val condName = processExpr(m.cond)
      val tvalName = processExpr(m.tval)
      val fvalName = processExpr(m.fval)
      s"$condName ? $tvalName : $fvalName"
    }
    case w: WSubField => s"${processExpr(w.exp)}.${w.name}"
    case p: DoPrim => p.op match {
      case Add => p.args map processExpr mkString(" + ")
      case Sub => p.args map processExpr mkString(" - ")
      case Mul => p.args map processExpr mkString(" * ")
      case Div => p.args map processExpr mkString(" / ")
      case Rem => p.args map processExpr mkString(" % ")
      case Lt  => p.args map processExpr mkString(" < ")
      case Leq => p.args map processExpr mkString(" <= ")
      case Gt  => p.args map processExpr mkString(" > ")
      case Geq => p.args map processExpr mkString(" >= ")
      case Eq => p.args map processExpr mkString(" == ")
      case Neq => p.args map processExpr mkString(" != ")
      case Pad => {
        if (p.consts.head.toInt > 64) throw new Exception("Pad too big!")
        processExpr(p.args.head)
      }
      case AsUInt => throw new Exception("AsUInt unimplemented!")
      case AsSInt => throw new Exception("AsSInt unimplemented!")
      case AsClock => throw new Exception("AsClock unimplemented!")
      case Shl => s"${processExpr(p.args.head)} << ${p.consts.head.toInt}"
      case Shr => s"${processExpr(p.args.head)} >> ${p.consts.head.toInt}"
      case Dshl => p.args map processExpr mkString(" << ")
      case Dshr => p.args map processExpr mkString(" >> ")
      case Cvt => throw new Exception("Cvt unimplemented!")
      case Neg => s"-${processExpr(p.args.head)}"
      case Not => s"~${processExpr(p.args.head)}"
      case And => p.args map processExpr mkString(" & ")
      case Or => p.args map processExpr mkString(" | ")
      case Xor => p.args map processExpr mkString(" ^ ")
      case Andr => throw new Exception("Andr unimplemented!")
      case Orr => throw new Exception("Orr unimplemented!")
      case Xorr => throw new Exception("Xorr unimplemented!")
      case Cat => {
        if (bitWidth(p.tpe) > 64) throw new Exception("Cat too big!")
        val shamt = bitWidth(p.args(1).tpe)
        s"(${processExpr(p.args(0))} << $shamt) | ${processExpr(p.args(1))}"
      }
      case Bits => {
        val hi_shamt = 64 - p.consts(0).toInt - 1
        val lo_shamt = p.consts(1).toInt + hi_shamt
        s"(${processExpr(p.args.head)} << $hi_shamt) >> $lo_shamt"
      }
      case Head => {
        val shamt = bitWidth(p.args.head.tpe) - p.consts.head.toInt
        s"${processExpr(p.args.head)} >> shamt"
      }
      case Tail => p.tpe match {
        case UIntType(_) => s"${processExpr(p.args.head)} & ${genMask(p.tpe)}"
        case SIntType(IntWidth(w)) => {
          val shamt = 64 - w
          s"(${processExpr(p.args.head)} << $shamt) >> $shamt;"
        }
      }
    }
    case _ => throw new Exception(s"Don't yet support $e")
  }

  def processStmt(s: Statement): Seq[String] = s match {
    case b: Block => b.stmts flatMap {s: Statement => processStmt(s)}
    case d: DefNode => {
      val lhs = genCppType(d.value.tpe) + " " + d.name
      val rhs = processExpr(d.value)
      Seq(s"$lhs = $rhs;")
    }
    case c: Connect => {
      val lhs = processExpr(c.loc)
      val rhs = processExpr(c.expr)
      firrtl.Utils.kind(c.loc) match {
        case firrtl.MemKind => Seq()
        case firrtl.RegKind => Seq(s"$lhs$$next = $rhs;")
        case firrtl.WireKind => Seq(s"${genCppType(c.loc.tpe)} $lhs = $rhs;")
        case _ => Seq(s"$lhs = $rhs;")
      }
    }
    case p: Print => {
      val printfArgs = Seq(s""""${p.string.serialize}"""") ++
                       (p.args map processExpr)
      Seq("if (update_registers)", tabs + s"if (${processExpr(p.en)})",
          tabs*2 + s"printf(${printfArgs mkString(", ")});")
    }
    case r: DefRegister => Seq()
    case w: DefWire => Seq()
    case m: DefMemory => Seq()
    case _ => throw new Exception(s"Don't yet support $s")
  }

  def grabMemInfo(s: Statement): Seq[(String, String)] = s match {
    case b: Block => b.stmts flatMap {s: Statement => grabMemInfo(s)}
    case c: Connect => {
      firrtl.Utils.kind(c.loc) match {
        case firrtl.MemKind => Seq((processExpr(c.loc), processExpr(c.expr)))
        case _ => Seq()
      }
    }
    case _ => Seq()
  }

  def buildGraph(commands: Seq[String]) = {
    val legalVarNames = """[a-zA-Z][a-zA-Z0-9\_\$]*""".r
    val g = new Graph
    commands foreach {cmd: String => {
      if (!cmd.contains("=")) throw new Exception(s"No assignment in $cmd")
      else {
        val split = cmd.split("=")
        val result = split(0).trim.split(" ").last
        val dependsOn = legalVarNames.findAllIn(split(1)).toSeq
        g.addNodeWithDeps(result, dependsOn, cmd)
      }
    }}
    g
  }

  def processBody(body: Statement, memories: Seq[DefMemory]) = {
    val memConnects = grabMemInfo(body).toMap
    val memWriteCommands = memories flatMap {m: DefMemory => {
      m.writers map { writePortName:String => {
        val wrEnName = memConnects(s"${m.name}.$writePortName.en")
        val wrAddrName = memConnects(s"${m.name}.$writePortName.addr")
        val wrDataName = memConnects(s"${m.name}.$writePortName.data")
        s"if ($wrEnName) ${m.name}[$wrAddrName] = $wrDataName;"
      }}
    }}
    val readOutputs = memories flatMap {m: DefMemory => {
      m.readers map { readPortName:String =>
        val rdAddrName = memConnects(s"${m.name}.$readPortName.addr")
        val rdDataName = s"${m.name}.$readPortName.data"
        (rdDataName, s"${m.name}[$rdAddrName]")
      }
    }}
    val readMappings = readOutputs.toMap
    val regularPortion = processStmt(body)
    val memReadsReplaced = regularPortion map { cmd: String => {
      if (!(readMappings.keys filter {k:String => cmd.contains(k)}).isEmpty) {
        readMappings.foldLeft(cmd){ case (s, (k,v)) => s.replaceAll(k,v)}
      }
      else cmd
    }}
    (memReadsReplaced, memWriteCommands)
  }

  def makeRegisterUpdate(r: DefRegister): String = {
    val regName = r.name
    val resetName = processExpr(r.reset)
    val resetVal = processExpr(r.init)
    if (resetName == "0x0") s"if (update_registers) $regName = $regName$$next;"
    else s"if (update_registers) $regName = $resetName ? $resetVal : $regName$$next;"
  }

  def harnessConnections(m: Module) = {
    // Attempts to reproduce the port ordering from chisel3 -> firrtl -> verilator
    // Reverses order of signals, but if signals are from same vec (share prefix
    // and have numeric suffix), reverses them (so vec signals are not reversed).
    def reorderPorts(signalNames: Seq[String]) = {
      def signalsFromSameVec(sigA: String, sigB: String) = {
        (sigA.count(_ == '_') > 1) && (sigB.count(_ == '_') > 1) &&
          (sigA.slice(0, sigA.lastIndexOf('_')) == sigB.slice(0, sigB.lastIndexOf('_'))) &&
          (sigA.drop(sigA.lastIndexOf('_')+1) forall Character.isDigit)
      }
      if (signalNames.isEmpty) signalNames
      else {
        val first = List(signalNames.head)
        val rest = signalNames.tail
        val contiguousPrefixes = rest.foldLeft(List[List[String]](first)) {
          (x,y) =>
            if (signalsFromSameVec(x.last.last, y)) x.init :+ (x.last ++ List(y))
            else x :+ List(y)
        }
        (contiguousPrefixes flatMap { _.reverse }).reverse
      }
    }
    def connectSignal(signalDirection: String)(signalName: String) = {
      s"comm->add_${signalDirection}signal(&${signalName});"
    }
    val signalNames = scala.collection.mutable.ArrayBuffer.empty[String]
    val inputNames = scala.collection.mutable.ArrayBuffer.empty[String]
    val outputNames = scala.collection.mutable.ArrayBuffer.empty[String]
    m.ports foreach {p => p.tpe match {
      case ClockType =>
      case _ => {
        if (p.name == "reset") signalNames += p.name
        else p.direction match {
          case Input => inputNames += p.name
          case Output => outputNames += p.name
        }
      }
    }}
    val modName = m.name
    // val registerNames = registers map {r: DefRegister => r.name}
    val internalNames = Seq("reset") //++ registerNames
    val mapConnects = (internalNames.zipWithIndex) map {
      case (label: String, index: Int) => s"""comm->map_signal("$modName.$label", $index);"""
    }
    (reorderPorts(inputNames) map connectSignal("in_")) ++
    (reorderPorts(outputNames) map connectSignal("out_")) ++
    (reorderPorts(signalNames) map connectSignal("")) ++ mapConnects
  }

  def initialVals(m: Module, registers: Seq[DefRegister], memories: Seq[DefMemory]) = {
    def initVal(name: String, tpe:Type) = tpe match {
      case UIntType(_) => s"$name = rand() & ${genMask(tpe)};"
      case SIntType(IntWidth(w)) => {
        val shamt = 64 - w
        s"$name = (rand() << $shamt) >> $shamt;"
      }
    }
    val regInits = registers map {
      r: DefRegister => initVal(r.name + "$next", r.tpe)
    }
    val memInits = memories map { m: DefMemory => {
      s"for (size_t a=0; a < ${m.depth}; a++) ${m.name}[a] = rand() & ${genMask(m.dataType)};"
    }}
    val portInits = m.ports flatMap { p => p.tpe match {
      case ClockType => Seq()
      case _ => Seq(initVal(p.name, p.tpe))
    }}
    regInits ++ memInits ++ portInits
  }

  def writeLines(indentLevel: Int, lines: String) {
    writeLines(indentLevel, Seq(lines))
  }

  def writeLines(indentLevel: Int, lines: Seq[String]) {
    lines foreach { s => writer write tabs*indentLevel + s + "\n" }
  }

  def processModule(m: Module) = {
    val registers = findRegisters(m.body)
    val memories = findMemory(m.body)
    val registerDecs = registers flatMap {d: DefRegister => {
      val typeStr = genCppType(d.tpe)
      val regName = d.name
      Seq(s"$typeStr $regName;", s"$typeStr $regName$$next;")
    }}
    val memDecs = memories map {m: DefMemory => {
      s"${genCppType(m.dataType)} ${m.name}[${m.depth}];"
    }}

    val modName = m.name
    writeLines(0, "")
    writeLines(0, s"typedef struct $modName {")
    writeLines(1, registerDecs)
    writeLines(1, memDecs)
    writeLines(1, m.ports flatMap processPort)
    writeLines(0, "")
    writeLines(1, s"$modName() {")
    writeLines(2, initialVals(m, registers, memories))
    writeLines(1, "}")
    writeLines(0, "")
    writeLines(1, "void eval(bool update_registers);")
    writeLines(1, s"void connect_harness(CommWrapper<struct $modName> *comm);")
    writeLines(0, s"} $modName;")
    val regUpdates = registers map makeRegisterUpdate
    val (body, memUpdates) = processBody(m.body, memories)
    (regUpdates, body, memUpdates)
  }

  def execute(circuit: Circuit, annotationMap: AnnotationMap): TransformResult = {
    val topName = circuit.main
    val headerGuardName = topName.toUpperCase + "_H_"
    writeLines(0, s"#ifndef $headerGuardName")
    writeLines(0, s"#define $headerGuardName")
    writeLines(0, "")
    writeLines(0, "#include <cstdint>")
    writeLines(0, "#include <cstdlib>")
    val moduleResults = circuit.modules map {
      case m: Module => processModule(m)
      case m: ExtModule => (Seq(), Seq(), Seq())
    }
    val (allRegUpdates, allBodies, allMemUpdates) = moduleResults.unzip3
    val reorderedBodies = buildGraph(allBodies.flatten).reorderCommands
    val topModule = circuit.modules.find(_.name == topName).get match {
      case m: Module => m
    }
    writeLines(0, "")
    writeLines(0, s"void $topName::eval(bool update_registers) {")
    writeLines(1, allRegUpdates.flatten ++ reorderedBodies ++ allMemUpdates.flatten)
    writeLines(0, "}")
    writeLines(0, "")
    writeLines(0, s"void $topName::connect_harness(CommWrapper<struct $topName> *comm) {")
    writeLines(1, harnessConnections(topModule))
    writeLines(0, "}")
    writeLines(0, "")
    writeLines(0, s"#endif  // $headerGuardName")
    println(circuit.serialize)
    TransformResult(circuit)
  }
}



// Copied from firrtl.EmitVerilogFromLowFirrtl
class FinalCleanups extends Transform with SimpleRun {
  val passSeq = Seq(
    passes.RemoveValidIf,
    passes.ConstProp,
    passes.PadWidths,
    passes.ConstProp,
    passes.Legalize,
    passes.VerilogWrap,
    passes.VerilogMemDelays,
    passes.ConstProp,
    passes.SplitExpressions,
    passes.CommonSubexpressionElimination,
    passes.DeadCodeElimination,
    passes.RemoveEmpty,
    WireConstProp)
    // passes.VerilogRename,
    // passes.VerilogPrep)
  def execute(circuit: Circuit, annotationMap: AnnotationMap): TransformResult = {
    run(circuit, passSeq)
  }
}



class CCCompiler extends Compiler {
  def transforms(writer: Writer): Seq[Transform] = Seq(
    new firrtl.Chisel3ToHighFirrtl,
    new firrtl.IRToWorkingIR,
    new firrtl.ResolveAndCheck,
    new firrtl.HighFirrtlToMiddleFirrtl,
    new firrtl.passes.InferReadWrite(TransID(-1)),
    new firrtl.passes.ReplSeqMem(TransID(-2)),
    new firrtl.MiddleFirrtlToLowFirrtl,
    new firrtl.passes.InlineInstances(TransID(0)),
    new FinalCleanups,
    new EmitCpp(writer)
  )
}