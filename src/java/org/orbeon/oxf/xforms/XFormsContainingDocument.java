/**
 *  Copyright (C) 2005 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.dom4j.Document;
import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.xforms.action.XFormsActionInterpreter;
import org.orbeon.oxf.xforms.event.*;
import org.orbeon.oxf.xforms.event.events.*;

import java.util.*;

/**
 * Represents an XForms containing document.
 *
 * The containing document includes:
 *
 * o XForms models (including multiple instances)
 * o XForms controls
 * o Event handlers hierarchy
 */
public class XFormsContainingDocument implements XFormsEventTarget, XFormsEventHandlerContainer {

    private List models;
    private Map modelsMap = new HashMap();
    private XFormsControls xformsControls;

    private XFormsModelSubmission activeSubmission;

    private XFormsActionInterpreter actionInterpreter;

    public XFormsContainingDocument(List models, Document controlsDocument) {
        this.models = models;
        this.xformsControls = new XFormsControls(this, controlsDocument);

        for (Iterator i = models.iterator(); i.hasNext();) {
            XFormsModel model = (XFormsModel) i.next();
            if (model.getId() != null)
                modelsMap.put(model.getId(), model);
            model.setContainingDocument(this);
        }
    }

    /**
     * Return model with the specified id, null if not found. If the id is the empty string, return
     * the default model, i.e. the first model.
     */
    public XFormsModel getModel(String modelId) {
        return (XFormsModel) ("".equals(modelId) ? models.get(0) : modelsMap.get(modelId));
    }

    /**
     * Get a list of all the models in this document.
     */
    public List getModels() {
        return models;
    }

    /**
     * Return the XForms controls.
     */
    public XFormsControls getXFormsControls() {
        return xformsControls;
    }

    /**
     * Initialize the XForms engine.
     */
    public void initialize(PipelineContext pipelineContext) {
        // NOP for now
    }

    /**
     * Get object with the id specified.
     */
    public Object getObjectById(PipelineContext pipelineContext, String id) {

        // Search in models
        for (Iterator i = models.iterator(); i.hasNext();) {
            XFormsModel model = (XFormsModel) i.next();
            final Object resultObject = model.getObjectByid(pipelineContext, id);
            if (resultObject != null)
                return resultObject;
        }

        // Search in controls
        return xformsControls.getObjectById(id);
    }

    /**
     * Return the active submission if any or null.
     */
    public XFormsModelSubmission getActiveSubmission() {
        return activeSubmission;
    }

    /**
     * Set the active submission.
     *
     * This can be called with a non-null value at most once.
     */
    public void setActiveSubmission(XFormsModelSubmission activeSubmission) {
        if (this.activeSubmission != null)
            throw new OXFException("There is already an active submission.");
        this.activeSubmission = activeSubmission;
    }

    /**
     * Execute an external event on element with id targetElementId and event eventName.
     */
    public void executeExternalEvent(PipelineContext pipelineContext, String eventName, String controlId, String otherControlId, String contextString) {

        // Get event target object
        final XFormsEventTarget eventTarget;
        {
            final Object eventTargetObject = getObjectById(pipelineContext, controlId);
            if (!(eventTargetObject instanceof XFormsEventTarget))
                throw new OXFException("Event target is not an XFormsEventTarget.");
            eventTarget = (XFormsEventTarget) eventTargetObject;
        }

        // Get other event target
        final XFormsEventTarget otherEventTarget;
        {
            final Object otherEventTargetObject = (otherControlId == null) ? null : getObjectById(pipelineContext, otherControlId);
            if (otherEventTargetObject == null)
                otherEventTarget = null;
            else if (!(otherEventTargetObject instanceof XFormsEventTarget))
                throw new OXFException("Other event target is not an XFormsEventTarget.");
            else
                otherEventTarget = (XFormsEventTarget) otherEventTargetObject;

        }

        // Create event
        final XFormsEvent xformsEvent = XFormsEventFactory.createEvent(eventName, eventTarget, otherEventTarget, contextString, null, null);

        // Interpret event
        interpretEvent(pipelineContext, xformsEvent);
    }

    private void interpretEvent(final PipelineContext pipelineContext, XFormsEvent xformsEvent) {
        final String eventName = xformsEvent.getEventName();
        if (XFormsEvents.XFORMS_DOM_ACTIVATE.equals(eventName)
            || XFormsEvents.XFORMS_DOM_FOCUS_OUT.equals(eventName)
            || XFormsEvents.XFORMS_DOM_FOCUS_IN.equals(eventName)
            || XFormsEvents.XFORMS_VALUE_CHANGED.equals(eventName)) {

            // These are events we allow directly from the client and actually handle

            dispatchEvent(pipelineContext, xformsEvent);

        } else if (XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE.equals(eventName)) {
            // 4.6.7 Sequence: Value Change with Focus Change

            final XXFormsValueChangeWithFocusChangeEvent concreteEvent = (XXFormsValueChangeWithFocusChangeEvent) xformsEvent;

            // 1. xforms-recalculate
            // 2. xforms-revalidate
            // 3. [n] xforms-valid/xforms-invalid; xforms-enabled/xforms-disabled; xforms-optional/xforms-required; xforms-readonly/xforms-readwrite
            // 4. xforms-value-changed
            // 5. DOMFocusOut
            // 6. DOMFocusIn
            // 7. xforms-refresh
            // Reevaluation of binding expressions must occur before step 3 above.

            // Set current context to control
            xformsControls.setBinding(pipelineContext, (XFormsControls.ControlInfo) concreteEvent.getTargetObject());

            // Set value into the instance
            XFormsInstance.setValueForNode(xformsControls.getCurrentSingleNode(), concreteEvent.getNewValue());

            // Dispatch events
            final XFormsModel model = xformsControls.getCurrentModel();
            dispatchEvent(pipelineContext, new XFormsRecalculateEvent(model, true));
            dispatchEvent(pipelineContext, new XFormsRevalidateEvent(model, true));

            dispatchEvent(pipelineContext, new XFormsValueChangeEvent(concreteEvent.getTargetObject()));
            if (concreteEvent.getOtherTargetObject() != null) {
                // We have a focus change (otherwise, the focus is assumed to remain the same)
                dispatchEvent(pipelineContext, new XFormsDOMFocusOutEvent(concreteEvent.getTargetObject()));
                dispatchEvent(pipelineContext, new XFormsDOMFocusInEvent(concreteEvent.getOtherTargetObject()));
            }

            dispatchEvent(pipelineContext, new XFormsRefreshEvent(model));

        } else if (XFormsEvents.XXFORMS_SUBMIT.equals(eventName)) {
            // Internal submission event
            dispatchEvent(pipelineContext, xformsEvent);

        } else {
            throw new OXFException("Invalid event dispatched by client: " + eventName);
        }
    }

    public void dispatchExternalEvent(final PipelineContext pipelineContext, XFormsEvent xformsEvent) {
        final String eventName = xformsEvent.getEventName();
        if (XFormsEvents.XXFORMS_INITIALIZE.equals(eventName)) {
            // 4.2 Initialization Events

            // 1. Dispatch xforms-model-construct to all models
            // 2. Dispatch xforms-model-construct-done to all models
            // 3. Dispatch xforms-ready to all models

            final String[] eventsToDispatch = { XFormsEvents.XFORMS_MODEL_CONSTRUCT, XFormsEvents.XFORMS_MODEL_CONSTRUCT_DONE, XFormsEvents.XFORMS_READY };
            for (int i = 0; i < eventsToDispatch.length; i++) {
                if (XFormsEvents.XFORMS_MODEL_CONSTRUCT_DONE.equals(eventsToDispatch[i])) {
                    dispatchExternalEvent(pipelineContext, new XXFormsInitializeControlsEvent(this));
                }
                for (Iterator j = getModels().iterator(); j.hasNext();) {
                    final XFormsModel currentModel = (XFormsModel) j.next();
                    dispatchEvent(pipelineContext, XFormsEventFactory.createEvent(eventsToDispatch[i], currentModel));
                }
            }
        } else if (XFormsEvents.XXFORMS_INITIALIZE_STATE.equals(eventName)) {
            // Restore models state
            for (Iterator j = getModels().iterator(); j.hasNext();) {
                final XFormsModel currentModel = (XFormsModel) j.next();
                dispatchEvent(pipelineContext, new XXFormsInitializeStateEvent(currentModel));
            }
            dispatchExternalEvent(pipelineContext, new XXFormsInitializeControlsEvent(this));
        } else if (XFormsEvents.XXFORMS_INITIALIZE_CONTROLS.equals(eventName)) {
            // Make sure controls are initialized
            xformsControls.initialize(pipelineContext);
        } else {
            throw new OXFException("Invalid event dispatched: " + eventName);
        }
    }

    public XFormsEventHandlerContainer getParentContainer() {
        return null;
    }

    public List getEventHandlers() {
        return null;
    }

    public String getId() {
        throw new OXFException("Method get() should not be called on XFormsContainingDocument.");
    }

    public void performDefaultAction(PipelineContext pipelineContext, XFormsEvent event) {
        throw new OXFException("Method performDefaultAction() should not be called on XFormsContainingDocument.");
    }

    /**
     * Main event dispatching entry.
     */
    public void dispatchEvent(PipelineContext pipelineContext, XFormsEvent event) {

        final XFormsEventTarget targetObject = (XFormsEventTarget) event.getTargetObject();

        // Find all event handler containers
        final List containers = new ArrayList();
        {
            XFormsEventHandlerContainer container = (targetObject instanceof XFormsEventHandlerContainer) ? (XFormsEventHandlerContainer) targetObject : targetObject.getParentContainer();
            while (container != null) {
                containers.add(container);
                container = container.getParentContainer();
            }
        }

        boolean propagate = true;
        boolean performDefaultAction = true;

        // Go from root to leaf
        Collections.reverse(containers);

        // Capture phase
        for (Iterator i = containers.iterator(); i.hasNext();) {
            final XFormsEventHandlerContainer container = (XFormsEventHandlerContainer) i.next();
            final List eventHandlers = container.getEventHandlers();

            if (eventHandlers != null) {
                if (container != targetObject) {
                    // Event listeners on the target which are in capture mode are not called

                    for (Iterator j = eventHandlers.iterator(); j.hasNext();) {
                        final XFormsEventHandler eventHandlerImpl = (XFormsEventHandler) j.next();

                        if (!eventHandlerImpl.isPhase() && eventHandlerImpl.getEventName().equals(event.getEventName())) {
                            // Capture phase match
                            eventHandlerImpl.handleEvent(pipelineContext, event);
                            propagate &= eventHandlerImpl.isPropagate();
                            performDefaultAction &= eventHandlerImpl.isDefaultAction();
                        }
                    }
                    // Cancel propagation if requested and if authorized by event
                    if (!propagate && event.isCancelable())
                        break;
                }
            }
        }

        // Go from leaf to root
        Collections.reverse(containers);

        // Bubbling phase
        if (propagate && event.isBubbles()) {
            for (Iterator i = containers.iterator(); i.hasNext();) {
                final XFormsEventHandlerContainer container = (XFormsEventHandlerContainer) i.next();
                final List eventHandlers = container.getEventHandlers();

                if (eventHandlers != null) {
                    for (Iterator j = eventHandlers.iterator(); j.hasNext();) {
                        final XFormsEventHandler eventHandlerImpl = (XFormsEventHandler) j.next();

                        if (eventHandlerImpl.isPhase() && eventHandlerImpl.getEventName().equals(event.getEventName())) {
                            // Bubbling phase match
                            eventHandlerImpl.handleEvent(pipelineContext, event);
                            propagate &= eventHandlerImpl.isPropagate();
                            performDefaultAction &= eventHandlerImpl.isDefaultAction();
                        }
                    }
                    // Cancel propagation if requested and if authorized by event
                    if (!propagate)
                        break;
                }
            }
        }

        // Perform default action is allowed to
        if (performDefaultAction || !event.isCancelable()) {
            targetObject.performDefaultAction(pipelineContext, event);
        }
    }

    /**
     * Execute an XForms action.
     *
     * @param pipelineContext       current PipelineContext
     * @param targetId              id of the target control
     * @param eventHandlerContainer event handler containe this action is running in
     * @param actionElement         Element specifying the action to execute
     */
    public void runAction(final PipelineContext pipelineContext, String targetId, XFormsEventHandlerContainer eventHandlerContainer, Element actionElement) {
        if (actionInterpreter == null)
            actionInterpreter = new XFormsActionInterpreter(this);
        actionInterpreter.runAction(pipelineContext, targetId, eventHandlerContainer, actionElement, null);
    }
}
