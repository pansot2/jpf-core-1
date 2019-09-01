package gov.nasa.jpf.listener;

import gov.nasa.jpf.PropertyListenerAdapter;
import gov.nasa.jpf.jvm.bytecode.MONITORENTER;
import gov.nasa.jpf.jvm.bytecode.MONITOREXIT;
import gov.nasa.jpf.search.Search;
import gov.nasa.jpf.util.DeepClone;
import gov.nasa.jpf.vm.ChoiceGenerator;
import gov.nasa.jpf.vm.FieldInfo;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.vm.VM;
import gov.nasa.jpf.vm.bytecode.FieldInstruction;
import gov.nasa.jpf.vm.choice.ThreadChoiceFromSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MyListener extends PropertyListenerAdapter {

    Node root = null;
    Node current = null;
    volatile private int depth = 0;
    volatile private int id = 0;
    HashMap<Long, Integer> parentLockRemovals = null;
    Set<String> allowDepth = null;
    Set<String> allowChild = null;
    Set<String> allowThreads = null;
    HashMap<String, ArrayList<String>> threadsDepMap = null;
    HashMap<Integer, ArrayList<Integer>> allowedPaths = null;
    int previousId = -1;
    ArrayList<Integer> statesHistory = new ArrayList<Integer>();
    ArrayList<String> statesAction = new ArrayList<String>();
    StateNode rootNode = new StateNode();
    HashMap<Integer, StateNode> stateMap = new HashMap<>();
    ArrayList<Integer> statesExcluded = new ArrayList<Integer>();
    ArrayList<Integer> statesIncluded = new ArrayList<Integer>();
    HashMap<Integer, Node> nodesIncluded = new HashMap<>();
    HashMap<Integer, Boolean> isEndState = new HashMap<>();
    HashMap<Integer, Integer> stateGroups = new HashMap<>();
    HashMap<String, Integer> stateGroupMap = new HashMap<>();
    HashMap<Integer, String> stateGroupMap2 = new HashMap<>();
    HashSet<Integer> firstElements = new HashSet<>();
    HashSet<String> finalSequences = new HashSet<>();
    HashSet<String> errors = new HashSet<>();
    HashMap<String, HashSet<String>> threadNames = new HashMap<>();
    int endNodes = 0;
    HashSet<String> fieldNames = null;

    public MyListener() {
        root = new Node();
        current = new Node();
        current.parent = root;
        root.children.add(current);
        allowedPaths = new HashMap<>();

        if (VM.getVM().getConfig().get("fieldNames") != null) {
            String[] fields = VM.getVM().getConfig().get("fieldNames").toString().split(",");
            if (fields != null) {
                fieldNames = new HashSet<String>(Arrays.asList(fields));
            }
        }

        if (allowThreads == null && VM.getVM().getConfig().get("vm.allowed.threads") != null) {
            String[] tids = VM.getVM().getConfig().get("vm.allowed.threads").toString().split(",");
            if (tids != null) {
                allowThreads = new HashSet<String>(Arrays.asList(tids));
            }

            if (threadsDepMap == null && VM.getVM().getConfig().get("vm.allowed.threads.seqdeps") != null) {
                String[] deps = VM.getVM().getConfig().get("vm.allowed.threads.seqdeps").toString().split(";");
                if (deps != null) {
                    threadsDepMap = new HashMap<>();
                    for (String s : deps) {
                        String d = s.split(":")[0];
                        String[] depList = s.split(":")[1].split(",");
                        for (String s2 : depList) {
                            if (threadsDepMap.get(s2) == null) {
                                ArrayList<String> d2 = new ArrayList();
                                d2.add(d);
                                threadsDepMap.put(s2, d2);
                            } else {
                                threadsDepMap.get(s2).add(d);
                            }
                        }

                    }
                }
            }
        }

        if (allowedPaths.size() == 0) {
            String[] allowedDepths = null;
            String[] allowedChildren = null;
            if (allowDepth == null && VM.getVM().getConfig().get("vm.parallel.allowed.depth") != null) {
                allowedDepths = VM.getVM().getConfig().get("vm.parallel.allowed.depth").toString().split(",");
                if (allowedDepths != null) {
                    allowDepth = new HashSet<String>(Arrays.asList(allowedDepths));
                }
            }
            if (allowChild == null && VM.getVM().getConfig().get("vm.parallel.allowed.child") != null) {
                allowedChildren = VM.getVM().getConfig().get("vm.parallel.allowed.child").toString().split(",\\[");
                if (allowedChildren != null) {
                    allowChild = new HashSet<String>(Arrays.asList(allowedChildren));
                }
            }

            if (allowDepth != null && allowChild != null) {

                if (allowedDepths.length == allowedChildren.length) {
                    for (int i = 0; i < allowedDepths.length; i++) {
                        String[] depthSeq = allowedDepths[i].split("-");
                        String[] childrenSeq = allowedChildren[i].replaceAll("\\[", "").replaceAll("\\]", "").split(",");

                        ArrayList<Integer> allowedChildrenArray = new ArrayList<>();
                        for (int j = 0; j < childrenSeq.length; j++) {
                            String[] childSeqRange = childrenSeq[j].split("-");
                            for (int k = Integer.parseInt(childSeqRange[0]); k <= Integer.parseInt(childSeqRange[childSeqRange.length - 1]); k++) {
                                allowedChildrenArray.add(k);
                            }
                        }
                        for (int j = Integer.parseInt(depthSeq[0]); j <= Integer.parseInt(depthSeq[depthSeq.length - 1]); j++) {
                            allowedPaths.put(j, allowedChildrenArray);
                        }
                    }
                } else {
                    System.err.println("Parameters allowed.depth and allowed.child should have the same length.");
                }
            }
        }

    }

    @Override
    public synchronized void choiceGeneratorSet(VM vm, ChoiceGenerator<?> cg) {
        Search search = vm.getSearch();

        id = search.getStateId();
        depth = search.getDepth();

        boolean found = current.findNode(id, depth);

        if (!found) {

            Node newN = new Node();
            newN.parent = current;

            newN.depth = depth;
            newN.id = id;
            newN.threadAccessed = (ArrayList<String>) current.threadAccessed.clone();

            if (cg instanceof ThreadChoiceFromSet) {

                ThreadInfo[] threads = ((ThreadChoiceFromSet) cg).getAllThreadChoices();
                for (int i = 0; i < threads.length; i++) {
                    ThreadInfo ti = threads[i];
                    if (!ti.hasChanged() && (threadsDepMap == null || threadsDepMap.get(search.getVM().getCurrentThread().getName()) == null)) {
                        continue;
                    }
                    if (allowThreads != null && !allowThreads.contains(ti.getName()) && (threadsDepMap == null || threadsDepMap.get(search.getVM().getCurrentThread().getName()) == null)) {
                        continue;
                    }

                    if (allowDepth != null && allowChild != null && allowedPaths.size() != 0) {
                        if (allowedPaths.containsKey(depth)) {

                            if (!allowedPaths.get(depth).contains(current.children.size())) {
                                ti.breakTransition(true);

                                continue;
                            }

                        }
                    }

                    Instruction insn = ti.getPC();

                    if (insn instanceof MONITORENTER) {
                        Data newD = new Data();
                        newD.locks = newN.getParentLockInfo(ti.getName(), current);
                        newD.lockRemovals = parentLockRemovals;

                        newD.fileLocation = insn.getFileLocation();
                        newD.lineNumber = insn.getLineNumber();
                        newD.methodName = insn.getMethodInfo().getName();
                        newD.className = insn.getMethodInfo().getClassName();
                        newD.packageName = insn.getMethodInfo().getClassInfo().getPackageName();
                        newD.isSynchronized = insn.getMethodInfo().isSynchronized();
                        newD.threadName = ti.getName();
                        if (!newN.threadAccessed.contains(ti.getName())) {
                            newN.threadAccessed.add(ti.getName());
                        }
                        newD.threadId = ti.getId();
                        newD.instance = insn.getMethodInfo().getClassInfo().getUniqueId();

                        MONITORENTER mentsinsn = (MONITORENTER) insn;
                        newD.isMonitorEnter = true;
                        newD.lockRef = mentsinsn.getLastLockRef();
                        newD.sourceLine = mentsinsn.getSourceLine();

                        newN.addThreadLock(newD, newD.threadName, newD.lockRef);
                        if (fieldNames != null) {
                            newN.data.add(newD);
                        }
                    }

                    if (insn instanceof MONITOREXIT) {
                        Data newD = new Data();
                        newD.locks = newN.getParentLockInfo(ti.getName(), current);
                        newD.lockRemovals = parentLockRemovals;

                        newD.fileLocation = insn.getFileLocation();
                        newD.lineNumber = insn.getLineNumber();
                        newD.methodName = insn.getMethodInfo().getName();
                        newD.className = insn.getMethodInfo().getClassName();
                        newD.packageName = insn.getMethodInfo().getClassInfo().getPackageName();
                        newD.isSynchronized = insn.getMethodInfo().isSynchronized();
                        newD.threadName = ti.getName();
                        if (!newN.threadAccessed.contains(ti.getName())) {
                            newN.threadAccessed.add(ti.getName());
                        }
                        newD.threadId = ti.getId();
                        newD.instance = insn.getMethodInfo().getClassInfo().getUniqueId();

                        MONITOREXIT mexinsn = (MONITOREXIT) insn;
                        newD.isMonitorExit = true;
                        newD.lockRef = mexinsn.getLastLockRef();
                        newD.sourceLine = mexinsn.getSourceLine();

                        newN.removeThreadLock(newD, newD.threadName, newD.lockRef);
                        if (fieldNames != null) {
                            newN.data.add(newD);
                        }
                    }

                    if (insn instanceof FieldInstruction) {

                        Data newD = new Data();
                        newD.locks = newN.getParentLockInfo(ti.getName(), current);
                        newD.lockRemovals = parentLockRemovals;

                        newD.fileLocation = insn.getFileLocation();
                        newD.lineNumber = insn.getLineNumber();
                        newD.methodName = insn.getMethodInfo().getName();
                        newD.isSynchronized = insn.getMethodInfo().isSynchronized();
                        newD.threadName = ti.getName();
                        if (!newN.threadAccessed.contains(ti.getName())) {
                            newN.threadAccessed.add(ti.getName());
                        }
                        newD.threadId = ti.getId();
                        newD.instance = insn.getMethodInfo().getClassInfo().getUniqueId();

                        FieldInstruction finsn = (FieldInstruction) insn;

                        if (finsn.isRead()) {
                            newD.readOperation = true;
                        } else {
                            newD.writeOperation = true;
                        }
                        FieldInfo fi = finsn.getFieldInfo();

                        newD.threadName = ti.getName();
                        newD.threadId = ti.getId();
                        newD.instance = insn.getMethodInfo().getClassInfo().getUniqueId();
                        newD.className = fi.getClassInfo().getSimpleName();
                        newD.packageName = fi.getClassInfo().getPackageName();
                        if (fieldNames != null) {
                            String name = fi.getFullName();
                            if (name.contains(".")) {
                                name = name.substring(name.lastIndexOf(".") + 1);
                            }
                            if (fieldNames.contains(name)) {
                                newD.fieldName = fi.getFullName();
                            } else {
                                newD.fieldName = null;
                            }
                        } else {
                            newD.fieldName = fi.getFullName();
                        }
                        newD.value = String.valueOf(finsn.getLastValue());
                        newD.type = finsn.getFieldInfo().getType();
                        newD.sourceLine = finsn.getSourceLine();

                        if (newD.fieldName != null && newD.fieldName.compareTo("gr.uop.gr.javamethodsjpf.ReentrantLock.num") == 0 && newD.writeOperation) {
                            if (newD.methodName.compareTo("lock") == 0) {
                                newN.addThreadLock(newD, newD.threadName, newD.instance);
                            } else if (newD.methodName.compareTo("unlock") == 0) {
                                if (newN.removeThreadLock(newD, newD.threadName, newD.instance)) {
                                    if (newD.lockRemovals.get(newD.instance) != null) {
                                        newD.lockRemovals.put(newD.instance, (int) newD.lockRemovals.get(newD.instance) + 1);
                                    } else {
                                        newD.lockRemovals.put(newD.instance, 1);
                                    }
                                }

                            }
                        }

                        if (newD.fieldName != null) {
                            newN.data.add(newD);
                        }
                    }

                }
                if (newN.data != null && newN.data.size() != 0) {
                    current.children.add(newN);
                    current = newN;
                    statesIncluded.add(cg.getStateId());
                    String nodeMap = newN.data.get(0).threadName + " " + newN.data.get(0).fieldName;
                    stateGroupMap2.put(cg.getStateId(), nodeMap);
                    if (!stateGroupMap.containsKey(nodeMap)) {
                        stateGroupMap.put(nodeMap, cg.getStateId());
                        stateGroups.put(cg.getStateId(), cg.getStateId());
                    } else {
                        if (!stateGroups.containsKey(cg.getStateId())) {
                            int keyNode = stateGroupMap.get(nodeMap);
                            stateGroups.put(cg.getStateId(), keyNode);
                        }
                    }
                    nodesIncluded.put(cg.getStateId(), newN);
                } else {
                    current.children.add(newN);
                    current = newN;
                    statesExcluded.add(cg.getStateId());
                }
            }

            previousId = id;
        }
    }

    @Override
    public synchronized void searchStarted(Search search) {
        System.out.println("----------------------------------- search started");
    }

    @Override
    public synchronized void stateAdvanced(Search search) {

        synchronized (this) {
            statesHistory.add(search.getVM().getStateId());
            statesAction.add("advanced " + search.getVM().isEndState());
        }

        if (allowThreads != null) {
            if (!allowThreads.contains(search.getVM().getCurrentThread().getName()) && (threadsDepMap == null || threadsDepMap.get(search.getVM().getCurrentThread().getName()) == null)) {

                search.getVM().ignoreState();
            } else if (!allowThreads.contains(search.getVM().getCurrentThread().getName())) {
                if (threadsDepMap != null && threadsDepMap.get(search.getVM().getCurrentThread().getName()) != null) {
                    current.findNode(search.getStateId(), search.getDepth());
                    boolean canBeTerminated = true;
                    for (String t : threadsDepMap.get(search.getVM().getCurrentThread().getName())) {
                        if (!current.threadAccessed.contains(t)) {
                            canBeTerminated = false;
                            break;
                        }
                    }
                    if (canBeTerminated) {
                        search.getVM().ignoreState();

                    }
                }
            }
        }
    }

    @Override
    public synchronized void stateBacktracked(Search search) {
        synchronized (this) {
            statesHistory.add(search.getVM().getStateId());
            statesAction.add("backtracked " + search.getVM().isEndState());
        }

    }

    int rootId = -100;

    @Override
    public synchronized void searchFinished(Search search) {
        System.out.println("----------------------------------- search finished");

        try {
            printTree(root);
            Thread.sleep(10000);

            checkFieldRule(root, new HashMap<String, HashMap<String, FieldState>>());

            System.out.println(errors.toString());
        } catch (Exception ex) {
            Logger.getLogger(MyListener.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    public void printTree(Node myNode) {

        List<Data> ld = myNode.data;

        System.out.println();
        System.out.print("id : " + myNode.id);
        System.out.print(" depth : " + myNode.depth);

        for (Data d : ld) {

            System.out.print(" fieldName : " + d.fieldName);
            System.out.print(" lineNumber : " + d.lineNumber);
            System.out.print(" methodName : " + d.methodName);
            System.out.print(" className : " + d.className);
            System.out.print(" instance : " + d.instance);
            System.out.print(" writeOperation : " + d.writeOperation);
            System.out.print(" readOperation : " + d.readOperation);
            System.out.print(" threadName : " + d.threadName);
            System.out.print(" isSynchronized : " + d.isSynchronized);
            System.out.print(" packageName : " + d.packageName);
            System.out.print(" fileLocation : " + d.fileLocation);
            System.out.print(" isMonitorEnter : " + d.isMonitorEnter);
            System.out.print(" isMonitorExit : " + d.isMonitorExit);
            System.out.print(" lockRef : " + d.lockRef);
            System.out.print(" value : " + d.value);
            System.out.print(" type : " + d.type);
            System.out.print(" sourceLine : " + d.sourceLine);
            System.out.print(" locks : " + (d.locks != null ? d.locks.toString() : null));
            System.out.print(" lockRemovals : " + (d.lockRemovals != null ? Arrays.asList(d.lockRemovals) : null));

        }

        for (Node nd : myNode.children) {
            printTree(nd);
        }
    }

    public void printData(Data d) {

        System.out.println();
        System.out.print(" fieldName : " + d.fieldName);
        System.out.print(" lineNumber : " + d.lineNumber);
        System.out.print(" methodName : " + d.methodName);
        System.out.print(" className : " + d.className);
        System.out.print(" instance : " + d.instance);
        System.out.print(" writeOperation : " + d.writeOperation);
        System.out.print(" readOperation : " + d.readOperation);
        System.out.print(" threadName : " + d.threadName);
        System.out.print(" isSynchronized : " + d.isSynchronized);
        System.out.print(" packageName : " + d.packageName);
        System.out.print(" fileLocation : " + d.fileLocation);
        System.out.print(" isMonitorEnter : " + d.isMonitorEnter);
        System.out.print(" isMonitorExit : " + d.isMonitorExit);
        System.out.print(" lockRef : " + d.lockRef);
        System.out.print(" value : " + d.value);
        System.out.print(" type : " + d.type);
        System.out.print(" sourceLine : " + d.sourceLine);
        System.out.print(" locks : " + (d.locks != null ? d.locks.toString() : null));
        System.out.print(" lockRemovals : " + (d.lockRemovals != null ? Arrays.asList(d.lockRemovals) : null));

    }

    class StateNode {

        int stateId = -1;
        int depth = -1;
        int currentSubpathNodes = 0;
        boolean visited = false;
        boolean isEndState = false;
        boolean isStateMatch = false;
        int realParent = -1;
        ArrayList<Integer> parrents = new ArrayList<>();
        ArrayList<Integer> children = new ArrayList<>();
    }

    private StateNode deepCloneStateNode(StateNode origin) {
        StateNode clone = new StateNode();
        clone.stateId = origin.stateId;
        clone.depth = origin.depth;
        clone.currentSubpathNodes = origin.currentSubpathNodes;
        clone.visited = origin.visited;
        clone.isEndState = origin.isEndState;
        clone.parrents = DeepClone.deepClone(origin.parrents);
        clone.children = DeepClone.deepClone(origin.children);

        return clone;
    }

    class Node {

        protected List<Data> data = new ArrayList<Data>();
        protected ArrayList<String> threadAccessed = new ArrayList<>();
        protected Node parent = null;
        protected List<Node> children = new ArrayList<Node>();
        protected int id = 0;
        protected int depth = 0;
        protected boolean locksSearched = false;

        public ArrayList<Long> getParentLockInfo(String thread, Node parent) {
            ArrayList<Long> parentLocks = null;

            if (parent != null) {
                Node nd = parent;

                for (Data d : nd.data) {
                    if (d.threadName.compareTo(thread) == 0 && d.locks != null) {
                        parentLocks = (ArrayList<Long>) d.locks.clone();
                        parentLockRemovals = new HashMap<>();
                        if (d.lockRemovals != null) {
                            parentLockRemovals.putAll(d.lockRemovals);
                        }

                        break;
                    }
                }

                if (parentLocks == null && nd.parent != null && !locksSearched) {
                    locksSearched = true;
                    parentLocks = getParentLockInfo(thread, nd.parent);
                    locksSearched = false;
                }
            }

            return parentLocks;
        }

        public void addThreadLock(Data d, String thread, long lockInstance) {
            ArrayList<Long> locks = d.locks;
            if (locks == null) {
                locks = new ArrayList<>();
                d.locks = locks;
            }

            if (!locks.contains(lockInstance)) {
                locks.add(lockInstance);
            }
        }

        public boolean removeThreadLock(Data d, String thread, long lockInstance) {
            boolean removal = false;
            ArrayList<Long> locks = d.locks;
            if (locks != null) {
                if (locks.remove(lockInstance)) {
                    removal = true;
                }
            }

            return removal;

        }

        public boolean findNode(int id, int depth) {
            boolean found = false;

            while (current.depth >= depth && current.depth != 0) {
                current = current.parent;
            }

            if (current.id != id) {
                for (Node nd : current.children) {
                    if (nd.id == id) {
                        current = nd;
                        found = true;
                        break;
                    }
                }
            } else {
                found = true;
            }

            return found;
        }

        public Node findNode(int id, int depth, Node parentNode) {
            Node found = parentNode;

            if (found.id != id) {
                for (Node nd : found.children) {
                    if (nd.id == id) {
                        found = nd;
                        break;
                    } else {
                        if (nd.depth > depth) {
                            break;
                        }
                        found = findNode(id, depth, nd);
                    }
                }
            }

            return found;
        }
    }

    class Data {

        String fieldName = null;
        String className = null;
        long instance = 0;
        boolean writeOperation = false;
        boolean readOperation = false;
        String threadName = null;
        String methodName = null;
        int threadId = -1;
        boolean isSynchronized = false;
        String fileLocation = null;
        int lineNumber = -1;
        String packageName = null;
        String value = null;
        String type = null;
        boolean isMonitorEnter = false;
        boolean isMonitorExit = false;
        int lockRef = -1;
        String sourceLine = null;
        ArrayList<Long> locks = new ArrayList<>();
        HashMap<Long, Integer> lockRemovals = new HashMap<>();
    }

    public void checkFieldRule(Node myNode, HashMap<String, HashMap<String, FieldState>> fieldStates) {

        ArrayList<String> checkFieldRule = new ArrayList<>();
        checkFieldRule.add("readField");
        checkFieldRule.add("threadChange");
        checkFieldRule.add("writeField");
        checkFieldRule.add("threadBack");
        checkFieldRule.add("writeField");

        List<Data> ld = myNode.data;
        FieldState state = null;

        for (Data d : ld) {

            if (threadNames.containsKey(d.fieldName)) {
                threadNames.get(d.fieldName).add(d.threadName);
            } else {
                HashSet<String> threadSet = new HashSet<>();
                threadSet.add(d.threadName);
                threadNames.put(d.fieldName, threadSet);
            }

            for (String threadN : threadNames.get(d.fieldName)) {
                if (d.fieldName != null) {

                    if (fieldStates.containsKey(d.fieldName)) {
                        if (fieldStates.get(d.fieldName).containsKey(threadN)) {
                            state = fieldStates.get(d.fieldName).get(threadN);
                        } else {
                            fieldStates.get(d.fieldName).put(threadN, new FieldState());
                            state = fieldStates.get(d.fieldName).get(threadN);
                            state.resetFieldRule(checkFieldRule);
                        }

                    } else {
                        fieldStates.put(d.fieldName, new HashMap<String, FieldState>());
                        fieldStates.get(d.fieldName).put(threadN, new FieldState());
                        state = fieldStates.get(d.fieldName).get(threadN);
                        state.resetFieldRule(checkFieldRule);

                    }

                    if (state.allThreadSeq.size() <= 1 || (state.allThreadSeq.size() == 2 && state.allThreadSeq.contains(d.threadName))) {
                        if (d.threadName.compareTo(threadN) == 0 && d.readOperation && state.checkFieldRule.size() != 0 && state.checkFieldRule.get(0).compareTo("readField") == 0) {
                            state.threadSeq.add(d.threadName);
                            state.checkFieldRule.remove(0);
                            state.lineNumberSeq.add(d.lineNumber);
                            state.allThreadSeq.add(d.threadName);
                            state.parentId = myNode.id;
                            state.parentDepth = myNode.depth;
                            if (VM.getVM().getConfig().get("vm.compilerop") != null && d.sourceLine != null) {
                                d.sourceLine = d.sourceLine.trim();
                                String fieldN = d.fieldName;
                                if (fieldN.contains(".")) {
                                    fieldN = fieldN.substring(fieldN.lastIndexOf(".") + 1);
                                }
                                if (d.sourceLine.contains("=")) {
                                    String[] fields = d.sourceLine.split("=");
                                    if (fields[0].contains(fieldN) && fields[1].contains(fieldN)) {
                                        state.resetFieldRule(checkFieldRule);
                                    }
                                } else if (d.sourceLine.contains(fieldN + "++") || d.sourceLine.contains(fieldN + "--")) {
                                    state.resetFieldRule(checkFieldRule);
                                }

                            }
                        } else if (d.threadName.compareTo(threadN) != 0 && state.checkFieldRule.size() != 0 && state.checkFieldRule.get(0).compareTo("threadChange") == 0 && state.checkFieldRule.get(1).compareTo("writeField") == 0) {
                            if (!state.threadSeq.contains(d.threadName) && d.writeOperation) {
                                state.checkFieldRule.remove(0);
                                state.checkFieldRule.remove(0);
                                state.lineNumberSeq.add(d.lineNumber);
                                state.allThreadSeq.add(d.threadName);

                            }
                        } else if (d.threadName.compareTo(threadN) == 0 && state.checkFieldRule.size() != 0 && state.checkFieldRule.get(0).compareTo("threadBack") == 0 && state.checkFieldRule.get(1).compareTo("writeField") == 0) {
                            if (state.threadSeq.get(0).compareTo(d.threadName) == 0 && d.writeOperation) {
                                state.checkFieldRule.remove(0);
                                state.checkFieldRule.remove(0);
                                state.lineNumberSeq.add(d.lineNumber);
                                state.allThreadSeq.add(d.threadName);

                            } else if (state.threadSeq.get(0).compareTo(d.threadName) == 0 && d.readOperation) {
                                boolean ignore = false;
                                if (VM.getVM().getConfig().get("vm.compilerop") != null && d.sourceLine != null) {
                                    d.sourceLine = d.sourceLine.trim();
                                    String fieldN = d.fieldName;
                                    if (fieldN.contains(".")) {
                                        fieldN = fieldN.substring(fieldN.lastIndexOf(".") + 1);
                                    }
                                    if (d.sourceLine.contains("=")) {
                                        String[] fields = d.sourceLine.split("=");
                                        if (fields[0].contains(fieldN) && fields[1].contains(fieldN)) {
                                            ignore = true;
                                        }
                                    } else if (d.sourceLine.contains(fieldN + "++") || d.sourceLine.contains(fieldN + "--")) {
                                        ignore = true;
                                    }
                                }
                                if (!ignore) {
                                    state.resetFieldRule(checkFieldRule);
                                }
                            }
                        }

                        if (state != null && state.checkFieldRule.isEmpty()) {
                            errors.add("Error pattern detected... " + d.lineNumber + " " + d.className + " " + d.fieldName + state.lineNumberSeq.toString() + " " + state.allThreadSeq.toString() + " " + state.log);
                            state.resetFieldRule(checkFieldRule);

                        }
                    }
                }
            }
        }

        for (Node nd : myNode.children) {
            checkFieldRule(nd, deepCloneStates(fieldStates));
        }

        for (HashMap.Entry<String, HashMap<String, FieldState>> entry : fieldStates.entrySet()) {

            for (HashMap.Entry<String, FieldState> entry2 : entry.getValue().entrySet()) {
                String key2 = entry2.getKey();
                FieldState value = entry2.getValue();
                value.allThreadSeq = null;
                value.checkFieldRule = null;
                value.threadSeq = null;
                value.lineNumberSeq = null;
                value = null;
            }
            entry = null;
        }
        fieldStates = null;
    }

    public void checkFieldRule2(Node myNode, HashMap<String, HashMap<String, FieldState>> fieldStates) {
        ArrayList<String> checkFieldRule = new ArrayList<>();
        checkFieldRule.add("readField");
        checkFieldRule.add("threadChange");
        checkFieldRule.add("writeField");
        checkFieldRule.add("threadBack");
        checkFieldRule.add("readField");

        List<Data> ld = myNode.data;
        FieldState state = null;

        for (Data d : ld) {
            if (threadNames.containsKey(d.fieldName)) {
                threadNames.get(d.fieldName).add(d.threadName);
            } else {
                HashSet<String> threadSet = new HashSet<>();
                threadSet.add(d.threadName);
                threadNames.put(d.fieldName, threadSet);
            }

            for (String threadN : threadNames.get(d.fieldName)) {
                if (d.fieldName != null) {

                    if (fieldStates.containsKey(d.fieldName)) {
                        if (fieldStates.get(d.fieldName).containsKey(threadN)) {
                            state = fieldStates.get(d.fieldName).get(threadN);
                        } else {
                            fieldStates.get(d.fieldName).put(threadN, new FieldState());
                            state = fieldStates.get(d.fieldName).get(threadN);
                            state.resetFieldRule(checkFieldRule);
                        }

                    } else {
                        fieldStates.put(d.fieldName, new HashMap<String, FieldState>());
                        fieldStates.get(d.fieldName).put(threadN, new FieldState());
                        state = fieldStates.get(d.fieldName).get(threadN);
                        state.resetFieldRule(checkFieldRule);

                    }

                    if (state.allThreadSeq.size() <= 1 || (state.allThreadSeq.size() == 2 && state.allThreadSeq.contains(d.threadName))) {
                        if (d.threadName.compareTo(threadN) == 0 && d.readOperation && state.checkFieldRule.size() != 0 && state.checkFieldRule.get(0).compareTo("readField") == 0) {
                            state.threadSeq.add(d.threadName);
                            state.checkFieldRule.remove(0);
                            state.lineNumberSeq.add(d.lineNumber);
                            state.allThreadSeq.add(d.threadName);
                            state.parentId = myNode.id;
                            state.parentDepth = myNode.depth;

                        } else if (d.threadName.compareTo(threadN) != 0 && state.checkFieldRule.size() != 0 && state.checkFieldRule.get(0).compareTo("threadChange") == 0 && state.checkFieldRule.get(1).compareTo("writeField") == 0) {
                            if (!state.threadSeq.contains(d.threadName) && d.writeOperation) {

                                state.checkFieldRule.remove(0);
                                state.checkFieldRule.remove(0);
                                state.lineNumberSeq.add(d.lineNumber);
                                state.allThreadSeq.add(d.threadName);
                                state.threadSeq.add(d.threadName);

                            }
                        } else if (d.threadName.compareTo(threadN) == 0 && state.checkFieldRule.size() != 0 && state.checkFieldRule.get(0).compareTo("threadChange") == 0 && state.checkFieldRule.get(1).compareTo("writeField") == 0) {
                            if (state.threadSeq.contains(d.threadName) && d.writeOperation) {
                                state.resetFieldRule(checkFieldRule);
                            }
                        } else if (d.threadName.compareTo(threadN) == 0 && state.checkFieldRule.size() != 0 && state.checkFieldRule.get(0).compareTo("threadBack") == 0 && state.checkFieldRule.get(1).compareTo("readField") == 0) {
                            if (state.threadSeq.get(0).compareTo(d.threadName) == 0 && d.readOperation) {
                                state.checkFieldRule.remove(0);
                                state.checkFieldRule.remove(0);
                                state.lineNumberSeq.add(d.lineNumber);
                                state.allThreadSeq.add(d.threadName);

                            } else if (state.threadSeq.get(0).compareTo(d.threadName) == 0 && d.writeOperation) {
                                state.resetFieldRule(checkFieldRule);

                            }
                        }

                        if (state != null && state.checkFieldRule.isEmpty()) {
                            errors.add("Error pattern detected... " + d.lineNumber + " " + d.className + " " + d.fieldName + state.lineNumberSeq.toString() + " " + state.allThreadSeq.toString() + " " + state.log);
                            state.resetFieldRule(checkFieldRule);

                        }
                    }
                }
            }
        }

        for (Node nd : myNode.children) {
            checkFieldRule2(nd, deepCloneStates(fieldStates));
        }

        for (HashMap.Entry<String, HashMap<String, FieldState>> entry : fieldStates.entrySet()) {

            for (HashMap.Entry<String, FieldState> entry2 : entry.getValue().entrySet()) {
                String key2 = entry2.getKey();
                FieldState value = entry2.getValue();
                value.allThreadSeq = null;
                value.checkFieldRule = null;
                value.threadSeq = null;
                value.lineNumberSeq = null;
                value = null;
            }
            entry = null;
        }
        fieldStates = null;

    }

    private HashMap<String, HashMap<String, FieldState>> deepCloneStates(HashMap<String, HashMap<String, FieldState>> fieldStates) {
        HashMap<String, HashMap<String, FieldState>> fieldStatesCloned = new HashMap<>();

        for (HashMap.Entry<String, HashMap<String, FieldState>> entry : fieldStates.entrySet()) {

            HashMap<String, FieldState> fieldStatesClonedPerThread = new HashMap<>();
            String key = entry.getKey();
            for (HashMap.Entry<String, FieldState> entry2 : entry.getValue().entrySet()) {
                FieldState fieldStateCloned = new FieldState();
                String key2 = entry2.getKey();
                FieldState value2 = entry2.getValue();
                fieldStateCloned.allThreadSeq = DeepClone.deepClone(value2.allThreadSeq);
                fieldStateCloned.checkFieldRule = DeepClone.deepClone(value2.checkFieldRule);
                fieldStateCloned.threadSeq = DeepClone.deepClone(value2.threadSeq);
                fieldStateCloned.lineNumberSeq = DeepClone.deepClone(value2.lineNumberSeq);
                fieldStateCloned.log = value2.log;
                fieldStateCloned.fType = value2.fType;
                fieldStateCloned.parentId = value2.parentId;
                fieldStateCloned.parentDepth = value2.parentDepth;
                fieldStatesClonedPerThread.put(key2, fieldStateCloned);
            }
            fieldStatesCloned.put(key, fieldStatesClonedPerThread);
        }

        return fieldStatesCloned;
    }

    class FieldState {

        public int parentId = -1;
        public int parentDepth = -1;
        ArrayList<String> checkFieldRule = new ArrayList<String>();
        ArrayList<String> threadSeq = new ArrayList<String>();
        ArrayList<Integer> lineNumberSeq = new ArrayList<Integer>();
        ArrayList<String> allThreadSeq = new ArrayList<String>();
        String log = "";
        String fType = null;

        public void resetFieldRule(ArrayList<String> fieldRule) {
            checkFieldRule = DeepClone.deepClone(fieldRule);
            threadSeq = new ArrayList<String>();
            lineNumberSeq = new ArrayList<Integer>();
            allThreadSeq = new ArrayList<String>();
            fType = null;
        }

        public void initializeFieldRule() {
            checkFieldRule = new ArrayList<String>();
            threadSeq = new ArrayList<String>();
            lineNumberSeq = new ArrayList<Integer>();
            allThreadSeq = new ArrayList<String>();
        }

        public FieldState deepClone() {
            FieldState fs = new FieldState();
            fs.checkFieldRule = (ArrayList<String>) DeepClone.deepClone(checkFieldRule);
            fs.threadSeq = (ArrayList<String>) DeepClone.deepClone(threadSeq);
            fs.lineNumberSeq = (ArrayList<Integer>) DeepClone.deepClone(lineNumberSeq);
            fs.allThreadSeq = (ArrayList<String>) DeepClone.deepClone(allThreadSeq);
            fs.log = this.log;
            fs.fType = this.fType;

            return fs;
        }

    }

}
