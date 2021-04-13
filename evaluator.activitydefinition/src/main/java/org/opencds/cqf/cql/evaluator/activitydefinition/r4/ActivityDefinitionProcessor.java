package org.opencds.cqf.cql.evaluator.activitydefinition.r4;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ActivityDefinition;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedArtifact;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.SupplyRequest;
import org.opencds.cqf.cql.engine.model.ModelResolver;
import org.opencds.cqf.cql.evaluator.builder.ModelResolverFactory;
import org.opencds.cqf.cql.evaluator.builder.data.FhirModelResolverFactory;
import org.opencds.cqf.cql.evaluator.fhir.dal.FhirDal;
import org.opencds.cqf.cql.evaluator.library.LibraryProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;

public class ActivityDefinitionProcessor {
    FhirContext fhirContext;
    FhirDal fhirDal;
    ModelResolver modelResolver;
    LibraryProcessor libraryProcessor;
    private static final Logger logger = LoggerFactory.getLogger(ActivityDefinitionProcessor.class);

    public ActivityDefinitionProcessor(FhirContext fhirContext, FhirDal fhirDal, LibraryProcessor libraryProcessor) {
        requireNonNull(fhirContext, "fhirContext can not be null");
        requireNonNull(fhirDal, "fhirDal can not be null");
        requireNonNull(libraryProcessor, "LibraryProcessor can not be null");
        this.fhirContext = fhirContext;
        this.fhirDal = fhirDal;
        this.libraryProcessor = libraryProcessor;
        ModelResolverFactory modelResolverFactory = new FhirModelResolverFactory();
        this.modelResolver = modelResolverFactory.create(fhirContext.getVersion().getVersion().getFhirVersionString());
    }

    public IBaseResource apply(IdType theId, String subjectId, String encounterId, String practitionerId,
             String organizationId, String userType, String userLanguage, String userTaskContext,
             String setting, String settingContext, IBaseParameters parameters, IBaseResource contentEndpoint,
             IBaseResource terminologyEndpoint, IBaseResource dataEndpoint)
            throws FHIRException, ClassNotFoundException, IllegalAccessException,
            InstantiationException {
        requireNonNull(subjectId, "subjectId can not be null");
        IBaseResource activityDefinitionResource = this.fhirDal.read(theId);
        ActivityDefinition activityDefinition = null;
        if (activityDefinitionResource != null && activityDefinitionResource instanceof ActivityDefinition) {
            activityDefinition = (ActivityDefinition) activityDefinitionResource;
        }

        if (activityDefinition == null) {
            throw new IllegalArgumentException("Couldn't find ActivityDefinition " + theId);
        }

        return resolveActivityDefinition(activityDefinition, subjectId, practitionerId, organizationId, parameters, contentEndpoint, terminologyEndpoint, dataEndpoint);
    }

    // For library use
    public Resource resolveActivityDefinition(ActivityDefinition activityDefinition, String patientId,
            String practitionerId, String organizationId, IBaseParameters parameters, IBaseResource contentEndpoint, IBaseResource terminologyEndpoint, IBaseResource dataEndpoint) throws FHIRException {
        Resource result = null;
        try {
            result = (Resource) Class.forName("org.hl7.fhir.r4.model." + activityDefinition.getKind().toCode()).getConstructor().newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            throw new FHIRException("Could not find org.hl7.fhir.r4.model." + activityDefinition.getKind().toCode());
        }

        switch (result.fhirType()) {
            case "ServiceRequest":
                result = resolveServiceRequest(activityDefinition, patientId, practitionerId, organizationId);
                break;

            case "MedicationRequest":
                result = resolveMedicationRequest(activityDefinition, patientId);
                break;

            case "SupplyRequest":
                result = resolveSupplyRequest(activityDefinition, practitionerId, organizationId);
                break;

            case "Procedure":
                result = resolveProcedure(activityDefinition, patientId);
                break;

            case "DiagnosticReport":
                result = resolveDiagnosticReport(activityDefinition, patientId);
                break;

            case "Communication":
                result = resolveCommunication(activityDefinition, patientId);
                break;

            case "CommunicationRequest":
                result = resolveCommunicationRequest(activityDefinition, patientId);
                break;
        }

        for (ActivityDefinition.ActivityDefinitionDynamicValueComponent dynamicValue : activityDefinition
                .getDynamicValue()) {
                    
                if (!dynamicValue.hasExpression()) {
                    logger.error("Missing condition expression");
                    throw new RuntimeException("Missing condition expression");
                }

                if (dynamicValue.getExpression().hasLanguage()) {
                    logger.info("Evaluating action condition expression " + dynamicValue.getExpression());
                    String cql = dynamicValue.getExpression().getExpression();
                    String language = dynamicValue.getExpression().getLanguage();
                    Object value = null;
                    switch (language) {
                        case "text/cql": logger.warn("CQL expression in PlanDefinition action not supported right now."); break;
                        case "text/cql.name": {
                            if (activityDefinition.getLibrary().size() != 1) {
                                throw new RuntimeException("ActivityDefinition library must only include one primary library for evaluation.");
                            }
                            String libraryUrl = activityDefinition.getLibrary().get(0).getValue();
                            Set<String> expressions = new HashSet<String>();
                            expressions.add(cql);
                            IBaseParameters parametersResult = libraryProcessor.evaluate(libraryUrl, patientId, parameters, contentEndpoint, terminologyEndpoint, dataEndpoint, null, expressions);
                            if (parametersResult == null) {
                                value = null; break;
                            }

                            Object parameter = modelResolver.resolvePath(parametersResult, "parameter");
                            if (parameter == null) {
                                value = null; break;
                            }

                            if (parameter instanceof Iterable<?>) {
                                Iterator<?> iterator = ((Iterable<?>)parameter).iterator();
                                if (iterator.hasNext()) {
                                    Object element = iterator.next();
                                    value = modelResolver.resolvePath(element, "value");
                                } else {
                                    value = null; break;
                                }
                            } else {
                                throw new RuntimeException("Invalid Paramter Object, parameter element must be an instance of Iterable, " + parameter);
                            }
                        } break;
                        default:
                        logger.warn(
                            "An action language other than CQL was found: " + dynamicValue.getExpression().getLanguage());
                        break;
                    }

                    if (value instanceof Boolean) {
                        value = new BooleanType((Boolean) value);
                    }
                    try {
                        this.modelResolver.setValue(result, dynamicValue.getPath(), value);  
                    } catch (Exception e) {
                        throw new RuntimeException(String.format("Could not set path %s to value: %s", dynamicValue.getPath(), value));
                    }
                }
        }

        return result;
    }

    private ServiceRequest resolveServiceRequest(ActivityDefinition activityDefinition, String patientId,
            String practitionerId, String organizationId) throws RuntimeException {
        // status, intent, code, and subject are required
        ServiceRequest serviceRequest = new ServiceRequest();
        serviceRequest.setStatus(ServiceRequest.ServiceRequestStatus.DRAFT);
        serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        serviceRequest.setSubject(new Reference(patientId));

        if (practitionerId != null) {
            serviceRequest.setRequester(new Reference(practitionerId));
        }

        else if (organizationId != null) {
            serviceRequest.setRequester(new Reference(organizationId));
        }

        if (activityDefinition.hasExtension()) {
            serviceRequest.setExtension(activityDefinition.getExtension());
        }

        if (activityDefinition.hasCode()) {
            serviceRequest.setCode(activityDefinition.getCode());
        }

        // code can be set as a dynamicValue
        else if (!activityDefinition.hasCode() && !activityDefinition.hasDynamicValue()) {
            throw new RuntimeException("Missing required code property");
        }

        if (activityDefinition.hasBodySite()) {
            serviceRequest.setBodySite(activityDefinition.getBodySite());
        }

        if (activityDefinition.hasProduct()) {
            throw new RuntimeException("Product does not map to " + activityDefinition.getKind());
        }

        if (activityDefinition.hasDosage()) {
            throw new RuntimeException("Dosage does not map to " + activityDefinition.getKind());
        }

        return serviceRequest;
    }

    private MedicationRequest resolveMedicationRequest(ActivityDefinition activityDefinition, String patientId)
            throws RuntimeException {
        // intent, medication, and subject are required
        MedicationRequest medicationRequest = new MedicationRequest();
        medicationRequest.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
        medicationRequest.setSubject(new Reference(patientId));

        if (activityDefinition.hasProduct()) {
            medicationRequest.setMedication(activityDefinition.getProduct());
        }

        else {
            throw new RuntimeException("Missing required product property");
        }

        if (activityDefinition.hasDosage()) {
            medicationRequest.setDosageInstruction(activityDefinition.getDosage());
        }

        if (activityDefinition.hasBodySite()) {
            throw new RuntimeException("BodySite does not map to " + activityDefinition.getKind());
        }

        if (activityDefinition.hasCode()) {
            throw new RuntimeException("Code does not map to " + activityDefinition.getKind());
        }

        if (activityDefinition.hasQuantity()) {
            throw new RuntimeException("Quantity does not map to " + activityDefinition.getKind());
        }

        return medicationRequest;
    }

    private SupplyRequest resolveSupplyRequest(ActivityDefinition activityDefinition, String practitionerId,
            String organizationId) throws RuntimeException {
        SupplyRequest supplyRequest = new SupplyRequest();

        if (practitionerId != null) {
            supplyRequest.setRequester(new Reference(practitionerId));
        }

        if (organizationId != null) {
            supplyRequest.setRequester(new Reference(organizationId));
        }

        if (activityDefinition.hasQuantity()) {
            supplyRequest.setQuantity(activityDefinition.getQuantity());
        }

        else {
            throw new RuntimeException("Missing required orderedItem.quantity property");
        }

        if (activityDefinition.hasCode()) {
            supplyRequest.setItem(activityDefinition.getCode());
        }

        if (activityDefinition.hasProduct()) {
            throw new RuntimeException("Product does not map to " + activityDefinition.getKind());
        }

        if (activityDefinition.hasDosage()) {
            throw new RuntimeException("Dosage does not map to " + activityDefinition.getKind());
        }

        if (activityDefinition.hasBodySite()) {
            throw new RuntimeException("BodySite does not map to " + activityDefinition.getKind());
        }

        return supplyRequest;
    }

    private Procedure resolveProcedure(ActivityDefinition activityDefinition, String patientId) {
        Procedure procedure = new Procedure();

        procedure.setStatus(Procedure.ProcedureStatus.UNKNOWN);
        procedure.setSubject(new Reference(patientId));

        if (activityDefinition.hasCode()) {
            procedure.setCode(activityDefinition.getCode());
        }

        if (activityDefinition.hasBodySite()) {
            procedure.setBodySite(activityDefinition.getBodySite());
        }

        return procedure;
    }

    private DiagnosticReport resolveDiagnosticReport(ActivityDefinition activityDefinition, String patientId) {
        DiagnosticReport diagnosticReport = new DiagnosticReport();

        diagnosticReport.setStatus(DiagnosticReport.DiagnosticReportStatus.UNKNOWN);
        diagnosticReport.setSubject(new Reference(patientId));

        if (activityDefinition.hasCode()) {
            diagnosticReport.setCode(activityDefinition.getCode());
        }

        else {
            throw new RuntimeException(
                    "Missing required ActivityDefinition.code property for DiagnosticReport");
        }

        if (activityDefinition.hasRelatedArtifact()) {
            List<Attachment> presentedFormAttachments = new ArrayList<>();
            for (RelatedArtifact artifact : activityDefinition.getRelatedArtifact()) {
                Attachment attachment = new Attachment();

                if (artifact.hasUrl()) {
                    attachment.setUrl(artifact.getUrl());
                }

                if (artifact.hasDisplay()) {
                    attachment.setTitle(artifact.getDisplay());
                }
                presentedFormAttachments.add(attachment);
            }
            diagnosticReport.setPresentedForm(presentedFormAttachments);
        }

        return diagnosticReport;
    }

    private Communication resolveCommunication(ActivityDefinition activityDefinition, String patientId) {
        Communication communication = new Communication();

        communication.setStatus(Communication.CommunicationStatus.UNKNOWN);
        communication.setSubject(new Reference(patientId));

        if (activityDefinition.hasCode()) {
            communication.setReasonCode(Collections.singletonList(activityDefinition.getCode()));
        }

        if (activityDefinition.hasRelatedArtifact()) {
            for (RelatedArtifact artifact : activityDefinition.getRelatedArtifact()) {
                if (artifact.hasUrl()) {
                    Attachment attachment = new Attachment().setUrl(artifact.getUrl());
                    if (artifact.hasDisplay()) {
                        attachment.setTitle(artifact.getDisplay());
                    }

                    Communication.CommunicationPayloadComponent payload = new Communication.CommunicationPayloadComponent();
                    payload.setContent(artifact.hasDisplay() ? attachment.setTitle(artifact.getDisplay()) : attachment);
                    communication.setPayload(Collections.singletonList(payload));
                }

            }
        }

        return communication;
    }

    private CommunicationRequest resolveCommunicationRequest(ActivityDefinition activityDefinition, String patientId) {
        CommunicationRequest communicationRequest = new CommunicationRequest();

        communicationRequest.setStatus(CommunicationRequest.CommunicationRequestStatus.UNKNOWN);
        communicationRequest.setSubject(new Reference(patientId));

        if (activityDefinition.hasCode()) {
            if (activityDefinition.getCode().hasText()) {
                communicationRequest.addPayload().setContent(new StringType(activityDefinition.getCode().getText()));
            }
        }

        return communicationRequest;
    }
}