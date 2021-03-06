package graphsc
import scala.util.Random

trait Hypergraph {
  // h should connect nodes known to the hypergraph
  // h.source may be a free node, in this case the function should add or find an appropriate node 
  // This function may perform transformations
  def addHyperedge(h: Hyperedge)
  
  // add a hyperedge, shortcut
  def add(l: Label, src: RenamedNode, ds: List[RenamedNode]): RenamedNode = {
    addHyperedge(Hyperedge(l, src, ds))
    src.deref
  }
  
  // Create a node with a hyperedge (or return an existing one)
  def add(l: Label, ds: List[RenamedNode]): RenamedNode = {
    val h = Hyperedge(l, null, ds).freeSource
    addHyperedge(h)
    h.source.deref
  }
  
  // Returns also a hyperedge
  def addH(l: Label, src: RenamedNode, ds: List[RenamedNode]): (RenamedNode, Hyperedge) = {
    val h = Hyperedge(l, src, ds)
    addHyperedge(h)
    (src.deref, normalize(h))
  }
  
  def addH(l: Label, ds: List[RenamedNode]): (RenamedNode, Hyperedge) = {
    val h = Hyperedge(l, null, ds).freeSource
    addHyperedge(h)
    (h.source.deref, normalize(h))
  }
  
  // Create a node without any connections
  def newNode(used: Set[Int]): RenamedNode
  
  // Nodes shouldn't be glued manually, they should be marked equal with 
  // an Id() hyperedge. Then the hypergraph should glue these nodes automatically.
  
  def onNewNode(n: Node) {}
  def onUsedReduced(n: Node) {}
  def onNewHyperedge(h: Hyperedge) {}
  def beforeGlue(l: RenamedNode, r: Node) {}
  def afterGlue(l: Node) {}
  
  def log(s: String) {}
  def nodeToString(n: RenamedNode): String = n.toString()
  def hyperedgeToString(h: Hyperedge): String = h.toString()
  
  def logShift() {}
  def logUnshift() {}
  
  def logTrans(name: String, hs: Seq[Hyperedge]) {
    log("-- transformation " + name)
    for(h <- hs)
      log("--   " + hyperedgeToString(h))
    log("")
  }
  
  def trans(name: String, hs: Hyperedge*)(body: =>Unit) {
    logTrans(name, hs)
    logShift()
    body
    logUnshift()
  }
  
  def glue(l: List[RenamedNode]): RenamedNode = l match {
    case List(r) => r
    case n1 :: n2 :: t => glue(add(Id(), n1, List(n2)) :: t) 
    case Nil => 
      throw new RuntimeException("List of nodes to glue must be non-empty")
  }
  
  // deprecated
  def allNodes: Set[Node] =
    null
  
  def allHyperedges: Set[Hyperedge] = {
    val sets = allNodes.toList.map(n => n.ins.toSet ++ n.outs.toSet)
    (Set[Hyperedge]() /: sets)(_ | _)
  }
  
  // RenamedNode representing ith variable
  def variable(i: Int): RenamedNode = {
    val res = Renaming(0 -> i) comp add(Var(), Nil)
    assert(res.used.size == 1)
    res
  }
  
  // RenamedNode representing unused expression
  def unused: RenamedNode =
    add(Unused(), Nil)
    
  // Replace vars which don't receive input with unused
  def varsToUnused(h: Hyperedge): Hyperedge = {
    val newdests =
      h.dests.map(d =>
        if(d.getVar == Some(-1)) unused
        else d)
    Hyperedge(h.label, h.source, newdests)
  }
  
  def normalize(h: Hyperedge): Hyperedge =
    canonize(simplify(h))._2
    
  // Hyperedge simplifier
  def simplify(h_underef: Hyperedge): Hyperedge = {
    val h = varsToUnused(h_underef.deref)
    h.label match {
      case Let() => 
        val newhead = h.dests(0).plain
        val newtail = 
          (0 until newhead.arity toList).map { i =>
            h.dests(0).renaming(i) match {
              case j if j < 0 || j >= h.dests.tail.size => unused
              case j => h.dests.tail(j)
            }
          }
        
        lazy val vec = newtail.map(_.getVarUnused.get)
        lazy val ren = Renaming(vec).normal
        lazy val renhead = ren comp newhead
        if(newtail.forall(_.getVarUnused.isDefined) && 
           vec.filter(_ >= 0).distinct.size == vec.filter(_ >= 0).size &&
           renhead.isInvertible) { 
          // Sometimes we cannot transform let to id
          // because it glues variables or assigns bottoms to some of them
          Hyperedge(Id(), h.source, List(renhead))
        }
        else
          Hyperedge(Let(), h.source, newhead :: newtail)
      case CaseOf(cases) =>
        h.dests(0).node.outs.find(o => 
          o.label.isInstanceOf[Construct] || o.label.isInstanceOf[Unused]) match {
          case Some(Hyperedge(Unused(), _, _)) =>
            Hyperedge(Unused(), h.source, Nil)
          case Some(Hyperedge(Construct(name), src2, args)) =>
            val ((_,n),branch) = (cases zip h.dests.tail).find(_._1._1 == name).get
            assert(n == args.size)
            val bs = 
              args.map(h.dests(0).renaming comp src2.renaming.inv comp _) ++ 
              (n until branch.arity).map(i => variable(i - n))
            
            simplify(Hyperedge(Let(), h.source, List(branch) ++ bs))
          case _ => h
        }
      case _ => h
    }
  }
  
  def canonize(ns: List[RenamedNode], shifts: List[Int]): (Renaming, List[RenamedNode]) = {
    require(ns.size == shifts.size)
    val varlist = (ns zip shifts).map {
      case (n,s) => n.renaming.vector.map(_ - s).filter(_ >= 0) }.flatten.distinct
    val map = varlist.zipWithIndex.toMap
    
    def varmap(i: Int): Int = 
      if(i < 0) i else map(i)
    
    (Renaming(varlist), (ns zip shifts).map { 
        case (RenamedNode(r,n),s) =>
          RenamedNode(Renaming(r.vector.map(i => varmap(i - s) + s)), n)
      })
  }
  
  def canonize(h: Hyperedge): (Renaming, Hyperedge) = h.label match {
    case Let() =>
      val (r, newtail) = canonize(h.dests.tail, h.dests.tail.map(_ => 0))
      (r, Hyperedge(Let(), r.inv comp h.source, h.dests(0) :: newtail))
    case Var() =>
      val r = Renaming(h.used)
      (r, h)
    case l => // CaseOf too
      val (r, newds) = canonize(h.dests, h.shifts)
      (r, Hyperedge(l, r.inv comp h.source, newds))
  }
}

trait TheHypergraph extends Hypergraph {
  val nodes = collection.mutable.Set[Node]()
  // This map maps zero-dested hyperedge labels to their sources
  val zeroHyper2Node = collection.mutable.Map[Label, RenamedNode]()
  
  override def allNodes: Set[Node] = nodes.toSet
  
  protected var varNode: RenamedNode = null
  protected var unusedNode: RenamedNode = null 
  
  override def variable(i: Int): RenamedNode = {
    if(varNode == null)
      varNode = super.variable(0)
    Renaming(0 -> i) comp varNode.deref
  }
  
  // RenamedNode representing error
  override def unused: RenamedNode = {
    if(unusedNode == null)
      unusedNode = super.unused
    unusedNode.deref
  }
  
  protected def addHyperedgeSimple(h: Hyperedge): Hyperedge = {
    val rinv = h.source.renaming.inv
    val sourcenode_used = rinv comp h.used
    
    assert(h.source.node.beingGlued || !h.label.isInstanceOf[Id])
    
    val res =
      if(h.source.node.isInstanceOf[FreeNode]) {
        val n = newNode(sourcenode_used)
        h.source.node.gluedTo = n
        h.from(h.source.renaming comp n)
      }
      else {
        h
      }
    
    res.source.node.outsMut += res
    res.dests.foreach(_.node.insMut.add(res))
    reduceUsed(res.source.node, sourcenode_used)
    // after reduction of used sets res can become undereferenced
    val ultimate = normalize(res)
    ultimate
  }
  
  protected def addHyperedgeImpl(h: Hyperedge) {
    require(h.source.node.isInstanceOf[FreeNode] || nodes(h.source.node))
    
    if(h.dests.nonEmpty)
      h.dests.minBy(_.node.insMut.size).node.insMut
      .find(x => x.label == h.label && x.dests == h.dests) match {
        case Some(x) if h.source.node == x.source.node => h.source
        case Some(x) => glueNodes(x.source, h.source)
        case None => 
          val newh = addHyperedgeSimple(h)
          checkIntegrity()
          onNewHyperedge(newh)
      }
    else
      zeroHyper2Node.get(h.label) match {
        case Some(n) =>
          glueNodes(n, h.source)
        case None => 
          val newh = addHyperedgeSimple(h)
          zeroHyper2Node += newh.label -> newh.source 
          checkIntegrity()
          onNewHyperedge(newh)
      }
  }
  
  override def addHyperedge(hUnnorm: Hyperedge) {
    val h = normalize(hUnnorm)
    val Hyperedge(l, src, ds) = h
    // a little note: if !src.isInvertible then the arity of src.node should be reduced
    // that is src is always invertible in some sense
    l match {
      case Id() =>
        // glue nodes even if ds(0) is not invertible
        // because unspecified variables of ds(0) are really unused
        glueNodes(src, ds(0))
      case _ =>
        // If S e1 == S e2 then e1 == e2
        if(glueChildren(h))
          src.deref
        else
          addHyperedgeImpl(h)
    }
  }
  
  override def newNode(used: Set[Int]): RenamedNode = {
    val n = new Node(used)
    nodes.add(n)
    onNewNode(n)
    n.deref
  }
  
  def removeNode(n: Node) {
    nodes -= n
    // we should leave n.outs and n.ins intact
    // so we remove all hyperedges incident with n
    // from all incident nodes except n
    for(h <- n.mins) {
      if(h.source.node != n)
        h.source.node.mouts -= h
      for(d <- h.dests if d.node != n)
        d.node.mins -= h
    }
    for(h <- n.mouts; d <- h.dests if d.node != n) {
      d.node.mins -= h
    }
  }
  
  def glueNodes(l1_underef: RenamedNode, r1_underef: RenamedNode): RenamedNode = {
    val (l1, r1) = (l1_underef.deref, r1_underef.deref)
    
    /*for((l1,r1) <- List((l1,r1), (r1,l1)))
      if(l1.node.isInstanceOf[FreeNode]) {
        assert(l1.node.gluedTo == null)
        //assert(nodes.contains(r1.node))
        l1.node.gluedTo = l1.renaming.inv comp r1
        return r1
      }*/
    if(l1.node.isInstanceOf[FreeNode]) {
      assert(l1.node.gluedTo == null)
      l1.node.gluedTo = l1.renaming.inv comp r1
      return r1
    }
    if(r1.node.isInstanceOf[FreeNode]) {
      assert(r1.node.gluedTo == null)
      r1.node.gluedTo = r1.renaming.inv comp l1
      return l1
    }
    
    //assert(nodes.contains(l1.node) && nodes.contains(r1.node))
    
    // TODO: Just for testing, should sort according to some simplicity measure
    //val List(l2,r2) = List(l1, r1).sortBy(_ => Random.nextBoolean())
    val List(l2,r2) = List(l1, r1).sortBy(x => (x.node.arity, -x.node.outs.size - x.node.ins.size))
    
    if(l2 != r2) {
      val nodesbefore = nodes.size
      val prettyl = l2.node.prettyDebug
      val prettyr = r2.node.prettyDebug
      checkIntegrity()
      
      // if one of the nodes is already being glued, we should restore this fact afterwards
      val beingGluedBefore = l2.node.beingGlued || r2.node.beingGlued  
      
      l2.node.beingGlued = true
      r2.node.beingGlued = true
      
      // We add temporary id hyperedges, so that HyperTester won't crash
      // This will also take care of arity reduction
      addHyperedgeSimple(normalize(Hyperedge(Id(), l2, List(r2))))
      addHyperedgeSimple(normalize(Hyperedge(Id(), r2, List(l2))))
      
      // Adding this hyperedges might trigger used set reduction
      // which in turn might perform node gluing
      val (l, r) = (l2.deref, r2.deref)
      
      // The node can be a renaming of itself, 
      // so we shouldn't do anything beyond adding hyperedges
      if(l.node == r.node)
        return l
      
      val l_renamed = r.renaming.inv comp l
      val r_node = r.node
      beforeGlue(l_renamed, r_node)
      
      removeNode(r_node)
      r_node.gluedTo = l_renamed
      
      // Readd hyperedges. We just deref them, they will be normalized in normalizeIncident
      // Turned out, ins and outs of the node may change during the readdition, so we should
      // remember these sets. It's not a good thing, the whole gluing process becomes very
      // unclear, I should think about redesigning it.
      val routs = r_node.mouts.toList
      val rins = r_node.mins.toList
      for(h <- routs)
        addHyperedgeSimple(canonize(h.deref)._2)
      for(h <- rins)
        addHyperedgeSimple(canonize(h.deref)._2)
      
      // Remove id hyperedges
      // Id endohyperedges are always redundant if they have id renamings
      l.deref.node.outsMut -= normalize(Hyperedge(Id(), l.plain, List(l.plain)))
      l.deref.node.insMut -= normalize(Hyperedge(Id(), l.plain, List(l.plain)))
      
      l.node.beingGlued = beingGluedBefore
        
      checkIntegrity()
      
      normalizeIncident(l.node)
      
      afterGlue(l.node.deref.node)
      
      // maybe there appeared some more nodes to glue 
      glueParents(l.node.deref.node)
      glueChildren(l.node.deref.node)
      
//      if(nodesbefore - nodes.size >= 20) {
//        println("This gluing was awesome:")
//        println(prettyl)
//        println("===")
//        println(prettyr)
//        println("difference " + (nodesbefore - nodes.size) + "\n")
//      }
      
      // Now l may be glued to something else
      l.deref
    }
    else //if(l == r)
      l2 // Nodes are already glued
  }
  
  // glue parents recursively
  def glueParents(n: Node) {
    val groups = n.ins.groupBy(h => (h.label, h.dests)).filter(_._2.size > 1)
    for((_, g) <- groups)
      g.toList.map(_.source).reduce(glueNodes)
  }
  
  // glue children recursively
  def glueChildren(n: Node) {
    for(h <- n.outs)
      glueChildren(normalize(h))
  }
  
  // Glue siblings of h that are equal to h 
  def glueChildren(h: Hyperedge): Boolean = h.label match {
    case l if isDefining(h) && !h.source.node.isInstanceOf[FreeNode] =>
      var done = false
      val src = h.source
      val ds = h.dests
      for {
        e <- src.node.outs
        if e != h
        if e.label == l && e.dests.size == ds.size
        if isDefining(e)
        val srcren = src.renaming comp e.source.renaming.inv
        val rens = 
          (ds,e.dests,e.shifts).zipped.map((d, d1, sh) => 
            d.renaming.inv comp srcren.shift(sh) comp d1.renaming)
      } {
        (ds,rens,e.dests).zipped.foreach((d,r,d1) => 
          glueNodes(d.plain, r comp d1.node))
        done = true
      }
      done
    case _ => false
  }
  
  // Normalize incident hyperedges which for some reason (gluing, used reduction) became non-normal
  def normalizeIncident(node_underef: Node) {
    val node = node_underef.deref.node
    val isvar = node.outs.exists(_.label.isInstanceOf[Var])
    var todo: List[() => Unit] = Nil
    for(h <- node.ins ++ node.outs) {
        val nor = normalize(h)
        if(nor != h) {
          // I don't remember why we used to perform hyperedge readdition after we've
          // removed old hyperedges, but actually it may break on the fly testing (because
          // "bad" hyperedges may be the only outgoing hyperedges)
          // Readding immediately after removing doesn't seem to break anything. 
          //todo = {() => addHyperedge(nor); ()} :: todo
          log("-- remove " + hyperedgeToString(h))
          h.source.deref.node.outsMut -= h
          h.dests.map(_.deref.node.insMut -= h)
          addHyperedge(nor)
        } else if(isvar && h.label.isInstanceOf[CaseOf]) {
          // here h is not non-normal but we can still perform important gluings
          todo = {() => glueChildren(h.deref); ()} :: todo
        }
      }
    // we perform these actions after removing bad hyperedges
    todo.foreach(_())
  }
  
  def reduceUsed(node: Node, set: Set[Int]) {
    require(nodes(node))
    val newused = node.used & set
      
    if(newused != node.used) {
      node.mused = newused
      
      // TODO: I don't know whether we should call it here or after re-adding hyperedges 
      onUsedReduced(node)
      normalizeIncident(node)
    }
  }
  
  // TODO: Move this function somewhere, it's very auxiliary
  def removeUnreachable(from: Node*) {
    val reachable = collection.mutable.Set[Node]()
    from.foreach(mark(_))
    def mark(n: Node) {
      if(!reachable(n)) {
        reachable += n
        for(h <- n.outs; d <- h.dests)
          mark(d.node)
      }
    }
    for(n <- nodes if !reachable(n))
      removeNode(n)
  }
  
  def nodeDotLabel(n: Node): String =
    n.uniqueName
  
  def toDot: String = {
    val sb = new StringBuilder()
    sb.append("digraph Hyper {\n")
    for(n <- nodes) {
      sb.append("\"" + n.uniqueName + "\"[label=\"" + nodeDotLabel(n) + "\", shape=box];\n")
      for(h <- n.outs) {
        def short(i: Int) = {
          val s = prettyRename(h.dests(i).renaming, h.dests(i).node.prettyDebug)
          if(s.size <= 6 && s != "")
            s.replaceAll("\\|", "\\\\|")
          else
            "@" + h.dests(i).node.uniqueName.dropWhile(_ != '@').tail.take(3)
        }
        
        val lab = "{" + h.source.renaming + "\\l" + h.label + "\\l|{" + 
            (0 until h.dests.length).map(i => "<" + i + ">" + short(i)).mkString("|") + "}}"
        sb.append("\"" + h.toString + "\"[label=\"" + lab + "\", shape=record];\n")
        sb.append("\"" + n.uniqueName + "\" -> \"" + h.toString + "\";\n")
        for((d,i) <- h.dests.zipWithIndex 
            if !d.node.outs.exists(h => h.label == Unused() || h.label.isInstanceOf[Var]) ||
               d.node.prettyDebug == "") {
          sb.append("\"" + h.toString + "\":" + i + " -> \"" + d + "\";\n")
          sb.append("\"" + d + "\"[label=\"" + d.renaming + "\", shape=box];\n")
          sb.append("\"" + d + "\"" + " -> \"" + d.node.uniqueName + "\";\n")
        }
      }
      sb.append("\n")
    }
    sb.append("}\n")
    sb.toString
  }
  // TODO: Move this function somewhere, it was copypasted from Prettifier
  private def prettyRename(r: Renaming, orig: String): String = {
    "v([0-9]+)v".r.replaceAllIn(orig, 
          { m =>
              val i = m.group(1).toInt
              if(r(i) < 0)
                "_|_"
              else
                "v" + r(i) + "v" })
  }
  
  def integrityCheckEnabled = false
  def checkIntegrity() {
    if(integrityCheckEnabled)
      for(n <- nodes) {
        assert(n.deref.node == n)
        /*for(h <- n.ins) {
          assert(nodes(h.source.node))
          assert(h.dests.forall(n => nodes(n.node)))
          assert(h.source.node.outs(h))
          assert(h.dests.forall(_.node.ins(h)))
        }*/
        for(h <- n.outs) {
          // Ids can be present in the hypergraph only during gluing
          assert(!h.label.isInstanceOf[Id] || 
              (h.source.node.beingGlued && h.dests(0).node.beingGlued))
          assert(nodes(h.source.node))
          assert(h.dests.forall(n => nodes(n.node)))
          assert(h.dests.forall(_.node.ins.contains(h)))
          assert(h.source.node == n)
          // h defines n. h cannot define n if its source has less variables than n
          // that's why we have arity reduction
          assert(h.source.isInvertible)
          //assert(h.used.subsetOf(h.source.used)) // not true currently
          if(h.label.isInstanceOf[CaseOf])
            assert(h.dests(0).getVar != Some(-1))
        }
      }
  }
}

trait IntegrityCheckEnabled extends TheHypergraph {
  override def integrityCheckEnabled = true
}