//===--- TestCallGraphShapeUsingJS.java ----------------------------------===//
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

package ca.maple.swan.swift.test;

import ca.maple.swan.swift.client.SwiftAnalysisEngine;
import ca.maple.swan.swift.translator.SwiftToCAstTranslator;
import ca.maple.swan.swift.translator.SwiftToCAstTranslatorFactory;
import com.ibm.wala.cast.ir.ssa.AstIRFactory;
import com.ibm.wala.cast.js.client.JavaScriptAnalysisEngine;
import com.ibm.wala.cast.js.ipa.modref.JavaScriptModRef;
import com.ibm.wala.cast.js.types.JavaScriptTypes;
import com.ibm.wala.cast.types.AstMethodReference;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Collection;
import java.util.Set;

import com.ibm.wala.cast.util.test.TestCallGraphShape;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.classLoader.SourceURLModule;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.slicer.SDG;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ssa.IRFactory;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.NullProgressMonitor;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.strings.Atom;

/*
 * This class currently serves as a testing bed (entry-point) for SWAN.
 */

public class TestSwiftCallGraphShapeUsingJS extends TestCallGraphShape {

    private static final SSAOptions options = new SSAOptions();
    private static final IRFactory irFactory = AstIRFactory.makeDefaultFactory();
    
    @Override
    public Collection<CGNode> getNodes(CallGraph CG, String functionIdentifier) {
        if (functionIdentifier.contains(":")) {
            String cls = functionIdentifier.substring(0, functionIdentifier.indexOf(":"));
            String name = functionIdentifier.substring(functionIdentifier.indexOf(":")+1);
            return CG.getNodes(MethodReference.findOrCreate(TypeReference.findOrCreate(JavaScriptTypes.jsLoader, TypeName.string2TypeName("L" + cls)), Atom.findOrCreateUnicodeAtom(name), AstMethodReference.fnDesc));
        } else {
            return CG.getNodes(MethodReference.findOrCreate(TypeReference.findOrCreate(JavaScriptTypes.jsLoader, TypeName.string2TypeName("L" + functionIdentifier)), AstMethodReference.fnSelector));
        }
    }

    protected SourceURLModule getScript(String name) throws IOException {
        try {
            File f = new File(name);
            if (f.exists()) {
                return new SourceURLModule(f.toURI().toURL());
            } else if (new File((name.substring(name.indexOf("/")+1).trim())).exists()) {
                return new SourceURLModule((new File((name.substring(name.indexOf("/")+1).trim())).toURI().toURL()));
            } else {
                throw new IOException(String.format("Script name (%s) is not a valid file!", name));
            }
        } catch (MalformedURLException e) {
            return new SourceURLModule(getClass().getClassLoader().getResource(name));
        }
    }

    protected JavaScriptAnalysisEngine createEngine() throws IllegalArgumentException {
        JavaScriptAnalysisEngine engine = new SwiftAnalysisEngine.SwiftPropagationJavaScriptAnalysisEngine();
        engine.setTranslatorFactory(new SwiftToCAstTranslatorFactory());
        return engine;

    }

    protected JavaScriptAnalysisEngine makeEngine(JavaScriptAnalysisEngine engine, String... name) throws IllegalArgumentException, IOException {
        Set<Module> modules = HashSetFactory.make();
        for(String n : name) {
            modules.add(getScript(n));
        }

        engine.setModuleFiles(modules);
        return engine;
    }

    protected JavaScriptAnalysisEngine makeEngine(String... name) throws IllegalArgumentException, IOException {
        return makeEngine(createEngine(), name);
    }

    static void dumpCHA(IClassHierarchy cha) {
        System.out.println("*** DUMPING CHA... ***");
        for (IClass c: cha) {
            System.out.println("<CLASS>"+c+"</CLASS");
            for (IMethod m: c.getDeclaredMethods()) {
                System.out.println("<METHOD>"+m+"</METHOD");
                System.out.println("<# ARGUMENTS>"+m.getNumberOfParameters()+"</# ARGUMENTS>");
                // TODO: This prints the CFG, we should just print the IR instructions here.
                //noinspection unchecked
                System.out.println(irFactory.makeIR(m, Everywhere.EVERYWHERE, options));
            }
        }
        System.out.println("*** ...FINISHED DUMPING CHA ***\n");
    }

    static void dumpCG(CallGraph CG) {
        System.out.println("*** DUMPING CG... ***");
        StringBuffer sb = new StringBuffer();
        for(CGNode n : CG) {
            sb.append(n.getIR()).append("\n");
        }
        System.out.println(sb);
        System.out.println("*** ...FINISHED DUMPING CG ***");
    }

    public static void main(String[] args) throws IllegalArgumentException {

        TestSwiftCallGraphShapeUsingJS driver = new TestSwiftCallGraphShapeUsingJS();

        JavaScriptAnalysisEngine Engine;

        try {

            String[] modules = new SwiftToCAstTranslator().doTranslation(args);

            if (modules.length == 0) {
                System.err.println("Error: could not create modules");
                System.exit(1);
            }

            Engine = driver.makeEngine(modules);
            CallGraphBuilder builder = Engine.defaultCallGraphBuilder();
            CallGraph CG = builder.makeCallGraph(Engine.getOptions(), new NullProgressMonitor());

            dumpCHA(CG.getClassHierarchy());

            @SuppressWarnings("unchecked") SDG<InstanceKey> sdg = new SDG<InstanceKey>(CG, builder.getPointerAnalysis(), new JavaScriptModRef<>(), Slicer.DataDependenceOptions.NO_BASE_NO_HEAP_NO_EXCEPTIONS, Slicer.ControlDependenceOptions.NONE);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}