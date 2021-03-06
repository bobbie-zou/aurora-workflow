package aurora.bpm.command;

import java.util.List;

import org.eclipse.bpmn2.ExclusiveGateway;
import org.eclipse.bpmn2.FlowElementsContainer;
import org.eclipse.bpmn2.FormalExpression;
import org.eclipse.bpmn2.SequenceFlow;

import aurora.bpm.command.beans.BpmnProcessToken;
import aurora.bpm.command.sqlje.PathProc;
import aurora.bpm.script.BPMScriptEngine;
import aurora.database.service.IDatabaseServiceFactory;
import aurora.sqlje.core.ISqlCallStack;

public class ExclusiveGatewayExecutor extends AbstractCommandExecutor {

	public ExclusiveGatewayExecutor(IDatabaseServiceFactory dsf) {
		super(dsf);
	}

	@Override
	public void executeWithSqlCallStack(ISqlCallStack callStack, Command cmd)
			throws Exception {
		// check and consume token
		PathProc p = createProc(PathProc.class, callStack);
		BpmnProcessToken token = p.getToken(
				cmd.getOptions().getLong(INSTANCE_ID),
				cmd.getOptions().getString(SEQUENCE_FLOW_ID));
		if (token == null) {
			// this will never happen on normal situation
			System.err.println("token not found:" + cmd);
			return;
		}
		p.consumeToken(token);

		String node_id = cmd.getOptions().getString(NODE_ID);
		org.eclipse.bpmn2.Process process = getRootProcess(
				loadDefinitions(cmd, callStack));
		FlowElementsContainer container = findFlowElementContainerById(process, cmd.getOptions().getString(SCOPE_ID));
		ExclusiveGateway eg = findFlowElementById(container, node_id,
				ExclusiveGateway.class);
		List<SequenceFlow> outgoings = eg.getOutgoing();
		SequenceFlow decision = null;
		BPMScriptEngine engine = prepareScriptEngine(callStack, cmd);
		engine.registry("process", process);
		engine.registry("currentNode", eg);

		for (SequenceFlow sf : outgoings) {
			if (sf == eg.getDefault())
				continue;
			FormalExpression exp = (FormalExpression) sf
					.getConditionExpression();
			String body = exp == null ? null : exp.getBody();
			if (body == null || body.length() == 0) {
				System.out.printf(
						"[exclusive gateway]%s,%s,condition is empty,always true\n",
						node_id, sf.getId());
				decision = sf;
				break;
			}
			Object ret = engine.eval(body);
			System.out.printf(
					"[exclusive gateway]%s,%s,condition execution result:%s\n",
					node_id, sf.getId(), "" + ret);
			if (ret instanceof Boolean && ((Boolean) ret).booleanValue()) {
				decision = sf;
				break;
			}
		}
		if (decision == null) {
			System.out.printf(
					"[exclusive gateway]%s,no conditions test success,try default..\n",
					node_id);
			decision = eg.getDefault();
		}

		if (decision == null) {
			System.out.printf(
					"[exclusive gateway]%s,no default sequence flow specified ,throw exception..\n",
					node_id);
			throw new RuntimeException(
					"no default sequence flow specified on ExclusiveGateway:"
							+ node_id);// TODO
		}
		System.out.printf("[exclusive gateway]%s,decision found,%s\n", node_id,
				decision.getId());

		createPath(callStack, decision, cmd);

	}

}
