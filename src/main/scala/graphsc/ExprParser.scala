package graphsc

import scala.util.parsing.combinator._
import graphsc.interpretation._

case class ExprParser(graph: NamedNodes) extends RegexParsers {
  def apply(s: String): Map[String, RenamedNode] = {
    val parsed = parseAll(prog, s)
    if(!parsed.successful) {
      // We've modified the graph even if the parsing wasn't successful
      System.err.println("Syntax error at " + parsed.next.pos + ": " + parsed.next.first)
      throw new Exception("Unsuccessful parse")
    }
    parsed.get.toMap.mapValues(_._1)
  }
  
  def assume(s: String): (H,H) = {
    val parsed = parseAll(equality, s)
    assert(parsed.successful)
    val (h1,h2) = parsed.get
    graph.glue(List(h1, h2))
    (h1,h2)
  }
  
  def check(s: String): Boolean = {
    val parsed = parseAll(equality, s)
    assert(parsed.successful)
    val (h1,h2) = parsed.get
    h1._1 == h2._1
  }
  
  def hyper(s: String): Hyperedge = {
    val parsed = parseAll(hyperedge, s)
    assert(parsed.successful)
    val h = parsed.get._2
    assert(h != null)
    h
  }
  
  override val whiteSpace = """(\s|--.*\n)+""".r
  
  type H = (RenamedNode, Hyperedge)
  private implicit def toRenamedNode(p: H): RenamedNode = p._1
  
  // TODO: Get rid of forall
  def equality: Parser[(H, H)] = 
    ("forall" ~> rep(fname) <~ ".") ~ (expr <~ "=") ~! expr ^^
    { case vs~e1~e2 =>
        val table = vs.zipWithIndex.toMap
        (e1(table), e2(table)) }
  
  def hyperedge: Parser[H] =
    (rep(fname) <~ ".") ~! expr ^^
    { case vs~e1 =>
        val table = vs.zipWithIndex.toMap
        e1(table) }
  
  def prog: Parser[List[(String, H)]] = 
    (repsep(decl, ";") <~ opt(";")).map(_.collect {case Some(x) => x})
  
  def decl: Parser[Option[(String, H)]] =
    eqdecl.map(_ => None) | testdecl.map(_ => None) | definition.map(Some(_))
  
  def testdecl: Parser[Unit] = 
    ("test:" ~> fname) ~ rep(const) ^^
    { case name~cs =>
        graph match {
          case ht: HyperTester => ht.runNode(graph(name), cs)
          case _ =>
        }}
    
  def eqdecl: Parser[Unit] = 
    ("forall" ~> rep(fname) <~ ".") ~ (expr <~ "=") ~! expr ^^
    { case vs~e1~e2 =>
        val table = vs.zipWithIndex.toMap
        graph.glue(List(e1(table)._1, e2(table)._1)) }
    
  def definition: Parser[(String, H)] = 
    (sign <~ "=") ~! expr ^^
    { case (name,node,table)~e =>
        val (n, hyp) = e(table)
        graph.glue(List(node, n))
        (name, (node.deref, graph.normalize(hyp))) }
  
  def sign: Parser[(String, RenamedNode, Map[String,Int])] =
    fname ~ rep(fname | "_") ^^
    { case name~vs => 
        (name, graph.newNode(name, vs.length.toInt), 
            vs.zipWithIndex.filter(_._1 != "_").toMap) }
  
  def fname = not("of\\b".r) ~> "[a-z][a-zA-Z0-9.@_]*".r
  def cname = "[A-Z][a-zA-Z0-9.@_]*".r
  
  private def theVar(v: Int): H = { 
    val n = graph.variable(v)
    (n, n.node.outs.find(_.label == Var()).get)
  }
  
  def onecase: Parser[Map[String,Int] => ((String, Int), H)] =
    cname ~ rep(fname) ~ "->" ~ expr ^^
    {case n~l~"->"~e => table =>
      val lsize = l.size
      val newtable = table.mapValues(_ + lsize) ++ (l zip (0 until lsize))
      ((n, lsize), e(newtable))}
  
  // TODO: now we cannot parse "case fun x of"
  def caseof: Parser[Map[String,Int] => H] =
    ("case" ~> expr <~ "of") ~! ("{" ~> repsep(onecase, ";") <~ opt(";") <~ "}") ^^
    { case e~lst => table =>
        val cases = lst.map(_(table)).sortBy(_._1)
        graph.addH(CaseOf(cases.map(_._1)), e(table) :: cases.map(_._2._1)) }
  
  def call: Parser[Map[String,Int] => H] =
    fname ~ rep(argexpr) ^^
    { case f~as => table =>
        if(as.isEmpty && table.contains(f))
          theVar(table(f))
        else {
          val fun = graph.newNode(f, as.length).deref
          graph.addH(Let(), fun :: as.map(_(table)._1))
        }
    }
  
  def variable: Parser[Map[String,Int] => H] =
    fname ^^
    { case f => table =>
        if(table.contains(f))
          theVar(table(f))
        else
          // We assume undefined variables to be zero-arg function
          (graph.newNode(f, 0).deref, null)
    }
  
  def unused: Parser[Map[String,Int] => H] =
    ("_|_" | "_" ) ^^
    { case _ => table =>
        graph.addH(Unused(), Nil)
    }
    
  def cons: Parser[Map[String,Int] => H] =
    cname ~ rep1(argexpr) ^^
    { case c~as => table =>
        graph.addH(Construct(c), as.map(_(table)._1)) }
  
  def zeroargCons: Parser[Map[String,Int] => H] =
    cname ^^
    { case c => table =>
        graph.addH(Let(), List(
            graph.add(Construct(c), List()))) }
  
  def expr: Parser[Map[String,Int] => H] =
    unused |
    caseof |
    "(" ~> expr <~ ")" |
    call |
    cons |
    zeroargCons
  
  def argexpr: Parser[Map[String,Int] => H] =
    variable |
    unused |
    caseof |
    zeroargCons |
    "(" ~> expr <~ ")"
  
  def const: Parser[Value] =
    (cname ^^ { case c => Ctr(c, Nil) }) |
    ("(" ~> cname ~ rep(const) <~ ")") ^^
      { case c~cs => Ctr(c, cs) }
}
