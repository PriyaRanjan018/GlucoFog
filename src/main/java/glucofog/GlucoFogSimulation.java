package glucofog;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.Config;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

/**
 * GlucoFog: fog-assisted hypo/hyperglycemia alert simulation (iFogSim2).
 *
 * Topology:  cloud (L0) -> clinic gateway (L1) -> patient hub x N (L2) -> CGM sensor + alert actuator
 *
 * Only the placement of the detection modules changes between modes:
 *   cloud  - every module runs in the cloud (baseline)
 *   clinic - detection runs on the clinic gateway
 *   edge   - detection runs on each patient's hub
 */
public class GlucoFogSimulation {

	enum Mode { CLOUD, CLINIC, EDGE }

	static final List<FogDevice> fogDevices = new ArrayList<FogDevice>();
	static final List<Sensor> sensors = new ArrayList<Sensor>();
	static final List<Actuator> actuators = new ArrayList<Actuator>();

	static Mode mode = Mode.EDGE;
	static int numOfPatients = 3;
	static double wanLatencyMs = 80;     // clinic gateway -> cloud
	static double homeLatencyMs = 50;    // patient hub -> clinic gateway
	static double cgmIntervalMs = 1000;  // one glucose reading per interval, per patient
	static int durationMs = 3600000;     // simulated time (1 hour)

	public static void main(String[] args) {
		parseArgs(args);
		System.out.println("CONFIG mode=" + mode.name().toLowerCase() + " patients=" + numOfPatients
				+ " wan_ms=" + wanLatencyMs + " home_ms=" + homeLatencyMs
				+ " interval_ms=" + cgmIntervalMs + " duration_ms=" + durationMs);
		try {
			Log.disable();
			// iFogSim stops the run and normalises network usage by this constant (default 2000 ms,
			// shorter than the sensors' second reading), so it has to follow the requested duration.
			Config.MAX_SIMULATION_TIME = durationMs;

			CloudSim.init(1, Calendar.getInstance(), false);

			String appId = "gluco_fog";
			FogBroker broker = new FogBroker("broker");
			Application application = createApplication(appId, broker.getId());
			application.setUserId(broker.getId());

			createFogDevices(broker.getId(), appId);

			Controller controller = new Controller("master-controller", fogDevices, sensors, actuators);
			ModuleMapping mapping = createModuleMapping();
			controller.submitApplication(application, 0, (mode == Mode.EDGE)
					? new ModulePlacementEdgewards(fogDevices, sensors, actuators, application, mapping)
					: new ModulePlacementMapping(fogDevices, application, mapping));

			TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
			CloudSim.startSimulation();
			CloudSim.stopSimulation();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	private static void parseArgs(String[] args) {
		try {
			for (int i = 0; i < args.length; i += 2) {
				String value = args[i + 1];
				switch (args[i]) {
					case "--mode":        mode = Mode.valueOf(value.toUpperCase()); break;
					case "--patients":    numOfPatients = Integer.parseInt(value); break;
					case "--wan-ms":      wanLatencyMs = Double.parseDouble(value); break;
					case "--home-ms":     homeLatencyMs = Double.parseDouble(value); break;
					case "--interval-ms": cgmIntervalMs = Double.parseDouble(value); break;
					case "--duration-ms": durationMs = Integer.parseInt(value); break;
					default: throw new IllegalArgumentException(args[i]);
				}
			}
		} catch (RuntimeException e) {
			System.err.println("usage: --mode cloud|clinic|edge --patients N --wan-ms X --home-ms X"
					+ " --interval-ms X --duration-ms X");
			System.exit(2);
		}
	}

	private static ModuleMapping createModuleMapping() {
		ModuleMapping m = ModuleMapping.createModuleMapping();
		switch (mode) {
			case CLOUD:
				for (String module : new String[] { "reading_filter", "hypo_hyper_detector",
						"clinic_aggregator", "cloud_analytics" }) {
					m.addModuleToDevice(module, "cloud");
				}
				break;
			case CLINIC:
				m.addModuleToDevice("reading_filter", "clinic-gateway");
				m.addModuleToDevice("hypo_hyper_detector", "clinic-gateway");
				m.addModuleToDevice("clinic_aggregator", "clinic-gateway");
				m.addModuleToDevice("cloud_analytics", "cloud");
				break;
			case EDGE:
				// reading_filter and hypo_hyper_detector are left to the edge-ward policy,
				// which pushes them onto each patient's hub.
				m.addModuleToDevice("clinic_aggregator", "clinic-gateway");
				m.addModuleToDevice("cloud_analytics", "cloud");
				break;
		}
		return m;
	}

	/** Physical topology: cloud -> clinic gateway -> per-patient hub. */
	private static void createFogDevices(int userId, String appId) {
		FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16 * 103, 16 * 83.25);
		cloud.setParentId(-1);
		fogDevices.add(cloud);

		FogDevice gateway = createFogDevice("clinic-gateway", 2800, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
		gateway.setParentId(cloud.getId());
		gateway.setUplinkLatency(wanLatencyMs);
		fogDevices.add(gateway);

		for (int i = 0; i < numOfPatients; i++) {
			addPatientHub(String.valueOf(i), userId, appId, gateway.getId());
		}
	}

	/** One patient: a fog hub, a CGM sensor (Bluetooth, 2 ms) and an alert actuator (1 ms). */
	private static void addPatientHub(String id, int userId, String appId, int parentId) {
		FogDevice hub = createFogDevice("hub-" + id, 1000, 1000, 10000, 270, 2, 0, 87.53, 82.44);
		hub.setParentId(parentId);
		hub.setUplinkLatency(homeLatencyMs);
		fogDevices.add(hub);

		Sensor cgm = new Sensor("cgm-sensor-" + id, "CGM_READING", userId, appId,
				new DeterministicDistribution(cgmIntervalMs));
		cgm.setGatewayDeviceId(hub.getId());
		cgm.setLatency(2.0);
		sensors.add(cgm);

		Actuator alert = new Actuator("alert-" + id, userId, appId, "ALERT");
		alert.setGatewayDeviceId(hub.getId());
		alert.setLatency(1.0);
		actuators.add(alert);
	}

	private static FogDevice createFogDevice(String name, long mips, int ram, long upBw, long downBw,
			int level, double ratePerMips, double busyPower, double idlePower) {
		List<Pe> peList = new ArrayList<Pe>();
		peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

		PowerHost host = new PowerHost(FogUtils.generateEntityId(), new RamProvisionerSimple(ram),
				new BwProvisionerOverbooking(10000), 1000000, peList, new StreamOperatorScheduler(peList),
				new FogLinearPowerModel(busyPower, idlePower));
		List<Host> hostList = new ArrayList<Host>();
		hostList.add(host);

		FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
				"x86", "Linux", "Xen", host, 5.5, 3.0, 0.05, 0.001, 0.0);
		try {
			FogDevice device = new FogDevice(name, characteristics, new AppModuleAllocationPolicy(hostList),
					new LinkedList<Storage>(), 10, upBw, downBw, 0, ratePerMips);
			device.setLevel(level);
			return device;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * CGM_READING -> reading_filter -> hypo_hyper_detector -> ALERT          (critical loop)
	 *                                        \-> clinic_aggregator -> cloud_analytics   (20% of readings, periodic)
	 */
	@SuppressWarnings("serial")
	private static Application createApplication(String appId, int userId) {
		Application app = Application.createApplication(appId, userId);
		for (String module : new String[] { "reading_filter", "hypo_hyper_detector",
				"clinic_aggregator", "cloud_analytics" }) {
			app.addAppModule(module, 10);
		}

		app.addAppEdge("CGM_READING", "reading_filter", 1000, 500, "CGM_READING", Tuple.UP, AppEdge.SENSOR);
		app.addAppEdge("reading_filter", "hypo_hyper_detector", 2000, 500, "FILTERED_GLUCOSE_DATA", Tuple.UP, AppEdge.MODULE);
		app.addAppEdge("hypo_hyper_detector", "ALERT", 1000, 100, "ALERT_COMMAND", Tuple.DOWN, AppEdge.ACTUATOR);
		app.addAppEdge("hypo_hyper_detector", "clinic_aggregator", 500, 1000, 3000, "PATIENT_SUMMARY", Tuple.UP, AppEdge.MODULE);
		app.addAppEdge("clinic_aggregator", "cloud_analytics", 1000, 2000, 6000, "CLINIC_SUMMARY", Tuple.UP, AppEdge.MODULE);

		app.addTupleMapping("reading_filter", "CGM_READING", "FILTERED_GLUCOSE_DATA", new FractionalSelectivity(1.0));
		app.addTupleMapping("hypo_hyper_detector", "FILTERED_GLUCOSE_DATA", "ALERT_COMMAND", new FractionalSelectivity(1.0));
		app.addTupleMapping("hypo_hyper_detector", "FILTERED_GLUCOSE_DATA", "PATIENT_SUMMARY", new FractionalSelectivity(0.2));
		app.addTupleMapping("clinic_aggregator", "PATIENT_SUMMARY", "CLINIC_SUMMARY", new FractionalSelectivity(1.0));

		final AppLoop alertLoop = new AppLoop(new ArrayList<String>() {{
			add("CGM_READING"); add("reading_filter"); add("hypo_hyper_detector"); add("ALERT");
		}});
		app.setLoops(new ArrayList<AppLoop>() {{ add(alertLoop); }});
		return app;
	}
}
