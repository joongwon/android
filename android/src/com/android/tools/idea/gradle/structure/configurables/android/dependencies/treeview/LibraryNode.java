/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.gradle.structure.configurables.android.dependencies.treeview;

import com.android.tools.idea.gradle.structure.model.PsdArtifactDependencySpec;
import com.android.tools.idea.gradle.structure.model.PsdModel;
import com.android.tools.idea.gradle.structure.model.android.PsdAndroidDependencyModel;
import com.android.tools.idea.gradle.structure.model.android.PsdLibraryDependencyModel;
import com.google.common.collect.Lists;
import com.intellij.ui.treeStructure.SimpleNode;
import org.jetbrains.annotations.NotNull;

import java.util.List;

class LibraryNode extends AbstractDependencyNode<PsdLibraryDependencyModel> {
  @NotNull private final List<SimpleNode> myChildren = Lists.newArrayList();

  LibraryNode(@NotNull PsdLibraryDependencyModel model) {
    super(model);

    PsdArtifactDependencySpec spec = model.getResolvedSpec();
    myName = spec.getDisplayText();

    for (PsdAndroidDependencyModel transitive : model.getTransitiveDependencies()) {
      if (transitive instanceof PsdLibraryDependencyModel) {
        PsdLibraryDependencyModel transitiveLibrary = (PsdLibraryDependencyModel)transitive;
        LibraryNode child = new LibraryNode(transitiveLibrary);
        myChildren.add(child);
      }
    }
  }

  @Override
  public SimpleNode[] getChildren() {
    return myChildren.toArray(new SimpleNode[myChildren.size()]);
  }

  @Override
  public boolean matches(@NotNull PsdModel model) {
    if (model instanceof PsdLibraryDependencyModel) {
      PsdLibraryDependencyModel other = (PsdLibraryDependencyModel)model;

      List<PsdLibraryDependencyModel> models = getModels();
      int modelCount = models.size();
      if (modelCount == 1) {
        PsdLibraryDependencyModel myModel = models.get(0);
        return myModel.getResolvedSpec().equals(other.getResolvedSpec());
      }
    }
    return false;
  }
}