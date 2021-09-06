/*
 * Copyright (c) 2021 the SWAN project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This software has dependencies with other licenses.
 * See https://github.com/themaplelab/swan/doc/LICENSE.md.
 */

package ca.ualberta.maple.swan.spds.swan

import ca.ualberta.maple.swan.ir._
import ca.ualberta.maple.swan.ir.canonical.SWIRLPass
import ca.ualberta.maple.swan.spds.analysis.boomerang.scene.Val.AllocVal
import ca.ualberta.maple.swan.spds.analysis.boomerang.{BackwardQuery, Boomerang, DefaultBoomerangOptions}
import ca.ualberta.maple.swan.spds.analysis.boomerang.scene.{CallGraph, ControlFlowGraph, DataFlowScope, Statement}
import ca.ualberta.maple.swan.utils.Logging

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class CallGraphConstruction(moduleGroup: ModuleGroup) {

  private final val uninterestingEntryPoints = Array(
    ".__deallocating_deinit",
    ".deinit",
    ".modify"
    // reabstraction thunk helper from*
  )

  private val methods = new mutable.HashMap[String, SWANMethod]()
  private val cg = new SWANCallGraph(moduleGroup, methods)
  private val mainEntryPoints = new mutable.HashSet[SWANMethod]
  private val otherEntryPoints = new mutable.HashSet[SWANMethod]

  private val builder = new SWIRLBuilder
  private var modified = false


  // TODO: verify if deterministic (usage of HashSets)
  // TODO: iOS lifecycle
  // TODO: Do separate RTA pass because it is not deterministic currently due
  //  to not only starting at entry point (fake main)
  // can also likely axe all *.__deallocating_deinit, *.deinit functions, and *.modify
  def construct(): (SWANCallGraph, mutable.HashMap[CanOperator, mutable.HashSet[String]], ModuleGroup, Option[(Module, CanModule)]) = {

    val debugInfo = new mutable.HashMap[CanOperator, mutable.HashSet[String]]()

    Logging.printInfo("Constructing Call Graph")
    val startTime = System.nanoTime()

    def makeMethod(f: CanFunction): SWANMethod = {
      val m = new SWANMethod(f, moduleGroup)
      methods.put(f.name, m)
      m
    }

    // Populate entry points
    mainEntryPoints.addAll(moduleGroup.functions.filter(f => f.name.startsWith(Constants.fakeMain)).map(f => makeMethod(f)))
    otherEntryPoints.addAll(moduleGroup.functions.filter(f => !f.name.startsWith(Constants.fakeMain)).map(f => makeMethod(f)))

    // Eliminate functions that get called (referenced), except for recursive case
    methods.foreach(m => {
      val f = m._2.delegate
      f.blocks.foreach(b => {
        b.operators.foreach(opDef => {
          opDef.operator match {
            case Operator.dynamicRef(_, index) => {
              moduleGroup.ddgs.foreach(ddg => {
                ddg._2.query(index, None).foreach(functionName => {
                  otherEntryPoints.remove(methods(functionName))
                })
              })
            }
            case Operator.builtinRef(_, name) => {
              if (methods.contains(name)) {
                otherEntryPoints.remove(methods(name))
              }
            }
            case Operator.functionRef(_, name) => {
              if (name != m._1) {
                otherEntryPoints.remove(methods(name))
              }
            }
            case _ =>
          }
        })
      })
    })
    // TODO: cg creates a problem if the entry points call something
    // Eliminate uninteresting functions
    /*val iterator = otherEntryPoints.iterator
    while (iterator.hasNext) {
      val m = iterator.next()
      uninterestingEntryPoints.foreach(s => {
        if (m.getName.endsWith(s)) {
          otherEntryPoints.remove(m)
          methods.remove(m.getName)
        }
      })
    }*/
    // Combine entry points, with the first being the main entry point
    val allEntryPoints = new ArrayBuffer[SWANMethod]
    allEntryPoints.appendAll(mainEntryPoints)
    allEntryPoints.appendAll(otherEntryPoints)
    allEntryPoints.foreach(e => cg.addEntryPoint(e))
    // Build CG for every entry point
    var trivialEdges = 0
    var virtualEdges = 0
    var queriedEdges = 0

    buildFromEntryPoints(allEntryPoints)

    def buildFromEntryPoints(entryPoints: ArrayBuffer[SWANMethod]): Unit = {

      entryPoints.foreach(entryPoint => {
        val instantiatedTypes = new mutable.HashSet[String]()
        // Mapping of methods to # of instantiated types
        val methodCount = new mutable.HashMap[SWANMethod, Int]
        // Mapping of block start stmts to # of instantiated types
        val blockCount = new mutable.HashMap[Statement, Int]

        def addCGEdge(from: SWANMethod, to: SWANMethod, stmt: SWANStatement.CallSite, cfgEdge: ControlFlowGraph.Edge[_ <: Statement, _ <: Statement]): Boolean = {
          val edge = new CallGraph.Edge(stmt, to)
          val b = cg.addEdge(edge)
          if (b) {
            stmt.updateInvokeExpr(to.getName, cg)
            val op = stmt.getDelegate.instruction match {
              case Instruction.canOperator(op) => op
              case _ => null // never
            }
            if (!debugInfo.contains(op)) {
              debugInfo.put(op, new mutable.HashSet[String]())
            }
            debugInfo(op).add(to.getName)
          }
          b
        }

        def queryRef(stmt: SWANStatement.CallSite, m: SWANMethod): Unit = {
          val ref = stmt.getInvokeExpr.asInstanceOf[SWANInvokeExpr].getFunctionRef
          val query = new BackwardQuery(
            new ControlFlowGraph.Edge(m.getCFG.getPredsOf(stmt).head, stmt), ref)
          val solver = new Boomerang(cg, DataFlowScope.INCLUDE_ALL, new DefaultBoomerangOptions)
          val backwardQueryResults = solver.solve(query.asInstanceOf[BackwardQuery])
          backwardQueryResults.getAllocationSites.foreach(x => {
            val forwardQuery = x._1
            val applyStmt = query.asNode.stmt.target.asInstanceOf[SWANStatement.CallSite]
            forwardQuery.variable.allocVal match {
              case v: SWANVal.FunctionRef => {
                val target = methods(v.ref)
                if (addCGEdge(m, target, applyStmt, query.cfgEdge)) queriedEdges += 1
                traverseMethod(target)
              }
              case v: SWANVal.DynamicFunctionRef => {
                moduleGroup.ddgs.foreach(ddg => {
                  val functionNames = ddg._2.query(v.index, Some(instantiatedTypes))
                  functionNames.foreach(name => {
                    val target = methods(name)
                    if (addCGEdge(m, target, applyStmt, query.cfgEdge)) queriedEdges += 1
                    traverseMethod(target)
                  })
                })
              }
              case v: SWANVal.BuiltinFunctionRef => {
                if (methods.contains(v.ref)) {
                  val target = methods(v.ref)
                  if (addCGEdge(m, target, applyStmt, query.cfgEdge)) queriedEdges += 1
                  traverseMethod(target)
                }
              }
              case _ => // likely result of partial_apply (ignore for now)
            }
          })
        }

        def traverseMethod(m: SWANMethod): Unit = {
          if (methodCount.contains(m)) {
            if (methodCount(m) == instantiatedTypes.size) {
              return
            }
          }
          methodCount.put(m, instantiatedTypes.size)
          instantiatedTypes.addAll(m.delegate.instantiatedTypes)

          def traverseBlock(b: ArrayBuffer[SWANStatement]): Unit = {
            if (blockCount.contains(b(0))) {
              if (blockCount(b(0)) == instantiatedTypes.size) {
                return
              }
            }
            blockCount.put(b(0), instantiatedTypes.size)
            b.foreach {
              case applyStmt: SWANStatement.CallSite => {
                val edge = new ControlFlowGraph.Edge(m.getCFG.getPredsOf(applyStmt).head, applyStmt)
                m.delegate.symbolTable(applyStmt.inst.functionRef.name) match {
                  case SymbolTableEntry.operator(_, operator) => {
                    operator match {
                      case Operator.functionRef(_, name) => {
                        val target = methods(name)
                        if (addCGEdge(m, target, applyStmt, edge)) trivialEdges += 1
                        traverseMethod(target)
                      }
                      case Operator.dynamicRef(_, index) => {
                        moduleGroup.ddgs.foreach(ddg => {
                          val functionNames = ddg._2.query(index, Some(instantiatedTypes))
                          functionNames.foreach(name => {
                            val target = methods(name)
                            if (addCGEdge(m, target, applyStmt, edge)) virtualEdges += 1
                            traverseMethod(target)
                          })
                        })
                      }
                      case Operator.builtinRef(_, name) => {
                        if (methods.contains(name)) {
                          val target = methods(name)
                          if (addCGEdge(m, target, applyStmt, edge)) trivialEdges += 1
                          traverseMethod(target)
                        }
                      }
                      case _ => queryRef(applyStmt, m)
                    }
                  }
                  case _: SymbolTableEntry.argument => queryRef(applyStmt, m)
                  case _: SymbolTableEntry.multiple => {
                    throw new RuntimeException("Unexpected application of multiple function references")
                  }
                }
              }
              case _ =>
            }
            m.getCFG.getSuccsOf(b.last).foreach(nextBlock => {
              traverseBlock(m.getCFG.blocks(nextBlock.asInstanceOf[SWANStatement])._1)
            })
          }

          val startStatement = m.getCFG.getStartPoints.head
          traverseBlock(m.getCFG.blocks(startStatement.asInstanceOf[SWANStatement])._1)
        }

        traverseMethod(entryPoint)
      })
    }

    Logging.printTimeStampSimple(1, startTime, "constructing")
    Logging.printInfo("  Entry Points:  " + cg.entryPoints.size.toString)
    Logging.printInfo("  Trivial Edges: " + trivialEdges.toString)
    Logging.printInfo("  Virtual Edges: " + virtualEdges.toString)
    Logging.printInfo("  Queried Edges: " + queriedEdges.toString)

    val toTraverse = new mutable.HashSet[String]

    dynamicIOSModels(toTraverse)
    // Add other dynamic modifications here

    var newValues: Option[(Module, CanModule)] = None

    var retModuleGroup = moduleGroup

    if (modified) {
      Logging.printInfo("Constructing Call Graph with added dynamic models")
      val startTime = System.nanoTime()

      val parser = new SWIRLParser(builder.toString)
      val newRawModule = parser.parseModule()
      val newCanModule = new SWIRLPass().runPasses(newRawModule)
      newCanModule.functions.foreach(f => {
        val m = methods(f.name)
        cg.edgesInto(m).foreach(e => cg.edges.remove(e))
        methods.remove(f.name)
        makeMethod(f)
      })
      val newModuleGroup = ModuleGrouper.group(ArrayBuffer(newCanModule), moduleGroup)
      cg.moduleGroup = newModuleGroup
      methods.values.foreach(m => m.moduleGroup = newModuleGroup)
      newValues = Some((newRawModule, newCanModule))
      val entryPoints = new ArrayBuffer[SWANMethod]()
      methods.values.foreach(m => if (toTraverse.contains(m.getName)) entryPoints.append(m))
      buildFromEntryPoints(entryPoints)
      retModuleGroup = newModuleGroup

      Logging.printInfo("  Entry Points:  " + cg.entryPoints.size.toString)
      Logging.printInfo("  Trivial Edges: " + trivialEdges.toString)
      Logging.printInfo("  Virtual Edges: " + virtualEdges.toString)
      Logging.printInfo("  Queried Edges: " + queriedEdges.toString)
      Logging.printTimeStampSimple(1, startTime, "constructing")
    }


    (cg, debugInfo, retModuleGroup, newValues)
  }

  private def dynamicIOSModels(toTraverse: mutable.HashSet[String]): Unit = {
    mainEntryPoints.foreach(f => toTraverse.add(f.getName))

    if (methods.contains("UIApplicationMain")) {
      val m = methods("UIApplicationMain")

      // Overwrite __allocating_init() to call init()
      val startStmt = m.getCFG.getStartPoints.head
      val edge = new ControlFlowGraph.Edge(startStmt, m.getCFG.getSuccsOf(startStmt).head)
      val query = new BackwardQuery(edge, m.getParameterLocal(3))
      val solver = new Boomerang(cg, DataFlowScope.INCLUDE_ALL, new DefaultBoomerangOptions)
      val results = solver.solve(query)
      if (results.getAllocationSites.isEmpty) {
        throw new RuntimeException("Expected argument to have allocation sites")
      }
      var allocatingInitFunction: SWANMethod = null
      var delegateType: String = null
      results.getAllocationSites.foreach(x => {
        val forwardQuery = x._1
        forwardQuery.variable.allocVal match {
          case c: SWANVal => {
            val tpe = c.getType.asInstanceOf[SWANType].tpe
            val regex = "@objc_metatype (.*)\\.Type".r
            val result = regex.findAllIn(tpe.name)
            if (result.nonEmpty) {
              delegateType = result.group(1)
              val allocatingInit = methods.find(x => x._2.getName.contains(delegateType+".__allocating_init()"))
              val init = methods.find(x => ("^[^\\s]*"+delegateType+"\\.init\\(\\)").r.findAllIn(x._2.getName).nonEmpty)
              if (allocatingInit.nonEmpty && init.nonEmpty) {
                allocatingInitFunction = allocatingInit.get._2
                builder.openFunction(allocatingInitFunction.delegate, model = true)
                builder.addLine("%1 = new $`"+delegateType+"`")
                builder.addLine("%2 = function_ref @`"+init.get._2.getName+"`, $`Any`")
                builder.addLine("%3 = apply %2(%1), $`"+delegateType+"`")
                builder.addLine("return %3")
                builder.closeFunction()
              } else {
                throw new RuntimeException("Expected (allocating) init method of delegate type to exist")
              }
            } else {
              throw new RuntimeException("Expected delegate type to match")
            }
          }
          case _ => throw new RuntimeException()
        }
      })

      // Overwrite UIApplicationMain stub to call allocating_init and lifecycle methods
      builder.openFunction(m.delegate, model = true)
      builder.addLine("%4 = function_ref @`"+allocatingInitFunction.getName+"`, $`Any`")
      builder.addLine("%5 = apply %4(%3), $`"+delegateType+"`")
      var i = 6
      val newValues = new mutable.HashMap[String, String]()
      val applyValues = new ArrayBuffer[(String, ArrayBuffer[String])]()
      methods.values.foreach(m => {
        if (m.getName.contains(delegateType)) {
          m.getParameterLocals.foreach(arg => {
            if (arg.getName == "self" && arg.getType.toString.contains("$"+delegateType)) {
              val args = new ArrayBuffer[String]()
              m.getParameterLocals.foreach(a => {
                if (a != arg) {
                  val tpe = a.getType.asInstanceOf[SWANType].tpe.name
                  if (!newValues.contains(tpe)) {
                    val v = "%"+i
                    i += 1
                    builder.addLine(v+" = new $`"+tpe+"`")
                    newValues.put(tpe, v)
                  }
                  args.append(newValues(tpe))
                } else {
                  args.append("%5")
                }
              })
              applyValues.append((m.getName, args))
            }
          })
        }
      })
      applyValues.foreach(v => {
        val v1 = "%"+i
        val v2 = "%"+(i+1)
        i += 2
        builder.addLine(v1+" = function_ref @`"+v._1+"`, $`Any`")
        builder.addLine(v2+" = apply "+v1+"("+v._2.mkString(", ")+"), $`Any`")
      })
      builder.addLine("%"+i+" = new $`Int32`")
      builder.addLine("return %"+i)
      builder.closeFunction()

      modified = true
    }
  }
}
