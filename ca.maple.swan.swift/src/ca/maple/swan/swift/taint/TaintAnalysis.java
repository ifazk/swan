//===--- TaintAnalysis.java ----------------------------------------------===//
//
// This source file is part of the SWAN open source project
//
// Copyright (c) 2019 Maple @ University of Alberta
// All rights reserved. This program and the accompanying materials (unless
// otherwise specified by a license inside of the accompanying material)
// are made available under the terms of the Eclipse Public License v2.0
// which accompanies this distribution, and is available at
// http://www.eclipse.org/legal/epl-v20.html
//
//===---------------------------------------------------------------------===//

package ca.maple.swan.swift.taint;

import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.tree.CAstSourcePositionMap;
import com.ibm.wala.cast.util.SourceBuffer;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.slicer.*;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.graph.traverse.BFSPathFinder;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static com.ibm.wala.ipa.slicer.Statement.Kind.NORMAL_RET_CALLER;

/*
 * This code is mostly borrowed from https://github.com/wala/Examples.
 * Currently, it can find the sources and sinks, but not a path in between. (TODO)
 *
 * TODO: Find a way to identify functions more generically (i.e. without the
 *       filename, output filename, etc).
 */

public class TaintAnalysis {

    static CallGraph CG = null;

    interface EndpointFinder {

        boolean endpoint(Statement s);

    }

    /************************************ EXAMPLES *************************************/
    public static EndpointFinder sendMessageSink = s -> {
        if (s.getKind()== Statement.Kind.PARAM_CALLER) {
            MethodReference ref = ((ParamCaller)s).getInstruction().getCallSite().getDeclaredTarget();
            return ref.getName().toString().equals("sendTextMessage");
        }

        return false;
    };

    public static EndpointFinder documentWriteSink = s -> {
        if (s.getKind()== Statement.Kind.PARAM_CALLEE) {
            String ref = s.getNode().getMethod().toString();
            return ref.equals("<Code body of function Lpreamble.js/DOMDocument/Document_prototype_write>");
        }

        return false;
    };

    public static EndpointFinder getDeviceSource = s -> {
        if (s.getKind()== NORMAL_RET_CALLER) {
            MethodReference ref = ((NormalReturnCaller)s).getInstruction().getCallSite().getDeclaredTarget();
            return ref.getName().toString().equals("getDeviceId");
        }

        return false;
    };

    public static EndpointFinder documentUrlSource = s -> {
        if (s.getKind()== Statement.Kind.NORMAL) {
            NormalStatement ns = (NormalStatement) s;
            SSAInstruction inst = ns.getInstruction();
            if (inst instanceof SSAGetInstruction) {
                return ((SSAGetInstruction) inst).getDeclaredField().getName().toString().equals("URL");
            }
        }

        return false;
    };
    /***************************** END OF EXAMPLES *************************************/

    public static final EndpointFinder swiftSources = s -> {
        if (s.getKind() == NORMAL_RET_CALLER) {
            CallSiteReference cs = ((NormalReturnCaller)s).getInstruction().getCallSite();
            CGNode node = s.getNode();
            Set<CGNode> it = CG.getPossibleTargets(node, cs);
            String testSource = "function Lmain/out.source() -> Swift.String";
            for (CGNode target: it) {
                if (target.getMethod().getDeclaringClass().toString().equals(testSource)) {
                    System.out.println("FOUND SOURCE:" + testSource);
                    return true;
                }
            }
        }
        return false;
    };

    public static final EndpointFinder swiftSinks = s -> {
        if (s.getKind()== Statement.Kind.PARAM_CALLEE) {
            String ref = s.getNode().getMethod().toString();
            String testSink = "<Code body of function Lmain/out.sink(sunk: Swift.String) -> ()>";
            if (ref.equals(testSink)) {
                System.out.println("FOUND SINK:" + testSink);
                return true;
            }
        }

        return false;
    };


    public static Set<List<Statement>> getPaths(SDG<? extends InstanceKey> G, EndpointFinder sources, EndpointFinder sinks) {
        Set<List<Statement>> result = HashSetFactory.make();
        CG = G.getCallGraph();
        for(Statement src : G) {
            if (sources.endpoint(src)) {
                for(Statement dst : G) {
                    if (sinks.endpoint(dst)) {
                        BFSPathFinder<Statement> paths = new BFSPathFinder<>(G, src, dst);
                        List<Statement> path = paths.find();
                        if (path != null) {
                            result.add(path);
                        }
                    }
                }
            }
        }
        return result;
    }

    public static void printPath(List<Statement> path) throws IOException {
        for(Statement s: path) {
            IMethod m = s.getNode().getMethod();
            boolean ast = m instanceof AstMethod;
            switch (s.getKind()) {
                case NORMAL: {
                    if (ast) {
                        CAstSourcePositionMap.Position p = ((AstMethod)m).getSourcePosition(((NormalStatement)s).getInstructionIndex());
                        SourceBuffer buf = new SourceBuffer(p);
                        System.out.println(buf + " (" + p + ")");
                    }
                    break;
                }
                case PARAM_CALLER: {
                    if (ast) {
                        CAstSourcePositionMap.Position p = ((AstMethod)m).getSourcePosition(((ParamCaller)s).getInstructionIndex());
                        SourceBuffer buf = new SourceBuffer(p);
                        System.out.println(buf + " (" + p + ")");
                    }
                    break;
                }
            }
        }
    }

    public static void printPaths(Set<List<Statement>> paths) throws IOException {
        for(List<Statement> path : paths) {
            printPath(path);
        }
    }
}