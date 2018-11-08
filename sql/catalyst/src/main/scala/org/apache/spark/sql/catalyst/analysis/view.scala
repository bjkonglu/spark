/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.analysis

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, Cast}
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Project, View}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.internal.SQLConf

/**
 * This file defines analysis rules related to views.
 */

/**
 * Make sure that a view's child plan produces the view's output attributes. We try to wrap the
 * child by:
 * 1. Generate the `queryOutput` by:
 *    1.1. If the query column names are defined, map the column names to attributes in the child
 *         output by name(This is mostly for handling view queries like SELECT * FROM ..., the
 *         schema of the referenced table/view may change after the view has been created, so we
 *         have to save the output of the query to `viewQueryColumnNames`, and restore them during
 *         view resolution, in this way, we are able to get the correct view column ordering and
 *         omit the extra columns that we don't require);
 *    1.2. Else set the child output attributes to `queryOutput`.
 * 2. Map the `queryOutput` to view output by index, if the corresponding attributes don't match,
 *    try to up cast and alias the attribute in `queryOutput` to the attribute in the view output.
 * 3. Add a Project over the child, with the new output generated by the previous steps.
 * If the view output doesn't have the same number of columns neither with the child output, nor
 * with the query column names, throw an AnalysisException.
 *
 * This should be only done after the batch of Resolution, because the view attributes are not
 * completely resolved during the batch of Resolution.
 */
case class AliasViewChild(conf: SQLConf) extends Rule[LogicalPlan] with CastSupport {
  override def apply(plan: LogicalPlan): LogicalPlan = plan resolveOperatorsUp {
    case v @ View(desc, output, child) if child.resolved && !v.sameOutput(child) =>
      val resolver = conf.resolver
      val queryColumnNames = desc.viewQueryColumnNames
      val queryOutput = if (queryColumnNames.nonEmpty) {
        // If the view output doesn't have the same number of columns with the query column names,
        // throw an AnalysisException.
        if (output.length != queryColumnNames.length) {
          throw new AnalysisException(
            s"The view output ${output.mkString("[", ",", "]")} doesn't have the same number of " +
              s"columns with the query column names ${queryColumnNames.mkString("[", ",", "]")}")
        }
        desc.viewQueryColumnNames.map { colName =>
          findAttributeByName(colName, child.output, resolver)
        }
      } else {
        // For view created before Spark 2.2.0, the view text is already fully qualified, the plan
        // output is the same with the view output.
        child.output
      }
      // Map the attributes in the query output to the attributes in the view output by index.
      val newOutput = output.zip(queryOutput).map {
        case (attr, originAttr) if !attr.semanticEquals(originAttr) =>
          // The dataType of the output attributes may be not the same with that of the view
          // output, so we should cast the attribute to the dataType of the view output attribute.
          // Will throw an AnalysisException if the cast can't perform or might truncate.
          if (Cast.mayTruncate(originAttr.dataType, attr.dataType)) {
            throw new AnalysisException(s"Cannot up cast ${originAttr.sql} from " +
              s"${originAttr.dataType.catalogString} to ${attr.dataType.catalogString} as it " +
              s"may truncate\n")
          } else {
            Alias(cast(originAttr, attr.dataType), attr.name)(exprId = attr.exprId,
              qualifier = attr.qualifier, explicitMetadata = Some(attr.metadata))
          }
        case (_, originAttr) => originAttr
      }
      v.copy(child = Project(newOutput, child))
  }

  /**
   * Find the attribute that has the expected attribute name from an attribute list, the names
   * are compared using conf.resolver.
   * If the expected attribute is not found, throw an AnalysisException.
   */
  private def findAttributeByName(
      name: String,
      attrs: Seq[Attribute],
      resolver: Resolver): Attribute = {
    attrs.find { attr =>
      resolver(attr.name, name)
    }.getOrElse(throw new AnalysisException(
      s"Attribute with name '$name' is not found in " +
        s"'${attrs.map(_.name).mkString("(", ",", ")")}'"))
  }
}

/**
 * Removes [[View]] operators from the plan. The operator is respected till the end of analysis
 * stage because we want to see which part of an analyzed logical plan is generated from a view.
 */
object EliminateView extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // The child should have the same output attributes with the View operator, so we simply
    // remove the View operator.
    case v @ View(_, output, child) =>
      assert(v.sameOutput(child),
        s"The output of the child ${child.output.mkString("[", ",", "]")} is different from the " +
          s"view output ${output.mkString("[", ",", "]")}")
      child
  }
}
