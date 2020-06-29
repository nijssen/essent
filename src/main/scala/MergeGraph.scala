package essent

import BareGraph.NodeID

import collection.mutable.{ArrayBuffer, HashMap, HashSet}


// Fundamental assumptions:
//  * IDs match up with IDs in original BareGraph
//  * The mergeID is a member of the merged group and ID used
//  * MergeGraph will not add new nodes after built

class MergeGraph extends BareGraph {
  // node ID -> merge ID
  val idToMergeID = ArrayBuffer[NodeID]()

  // merge ID -> [member] node IDs (must include mergeID)
  val mergeIDToMembers = HashMap[NodeID, Seq[NodeID]]()

  // inherits outNeigh and inNeigh from BareGraph

  def buildFromInitialAssignments(initialAssignments: ArrayBuffer[NodeID]) {
    // FUTURE: support negative (unassigned) initial assignments
    initialAssignments.copyToBuffer(idToMergeID)
    val asMap = Util.groupIndicesByValue(initialAssignments)
    asMap foreach { case (mergeID, members) => {
      assert(members.contains(mergeID))
      mergeIDToMembers(mergeID) = members
      mergeNodesMutably(mergeID, members diff Seq(mergeID))
    }}
  }

  def mergeGroups(mergeDest: NodeID, mergeSources: Seq[NodeID]) {
    val newMembers = (mergeSources map mergeIDToMembers).flatten
    newMembers foreach { id => idToMergeID(id) = mergeDest}
    mergeIDToMembers(mergeDest) ++= newMembers
    mergeSources foreach { id => mergeIDToMembers.remove(id) }
    mergeNodesMutably(mergeDest, mergeSources)
  }
}


object MergeGraph {
  def apply(og: BareGraph, initialAssignments: ArrayBuffer[NodeID]) = {
    // FUTURE: cleaner way to do this with clone on superclass?
    val mg = new MergeGraph
    og.outNeigh.copyToBuffer(mg.outNeigh)
    og.inNeigh.copyToBuffer(mg.inNeigh)
    mg.buildFromInitialAssignments(initialAssignments)
    mg
  }
}