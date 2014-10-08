package agent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import map.CityMap;
import map.GarbageContainer;
import map.Point;
import map.Road;
import ai.Goal;
import ai.Options;
import ai.Path;
import ai.Plan;
import units.Truck;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.SimpleBehaviour;
import jade.domain.AMSService;
import jade.domain.FIPAAgentManagement.AMSAgentDescription;
import jade.domain.FIPAAgentManagement.SearchConstraints;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

public class TruckAgent extends Agent{
	
	private static final long serialVersionUID = 2394071613389642100L;
	
	private Truck truck;
	private String truckFilename;
	private Options options;
	
	public Truck getTruck() {
		return truck;
	}
	
	public void setTruck(Truck t){
		this.truck = t;
	}
	
	public String getTruckFilename() {
		return truckFilename;
	}

	public void setTruckFilename(String truckFilename) {
		this.truckFilename = truckFilename;
	}

	public AID getAIDFromTruckName(Truck t){
		return getAID();
	}
	
	protected void setup() {
		Object[] args = getArguments();
		this.truck = (Truck) args[0];
		this.truckFilename = "truck" + this.truck.getTruckName() + ".xml";
		
		System.out.println("My name is " + this.getAID().getName() + " and I'm active now.");
		
		this.options = new Options();
		try {
			this.options.importOptions("options.xml");
		} catch (ParserConfigurationException | SAXException | IOException e) {
			e.printStackTrace();
		}
		
		if(options.isAgentsKnowMap()) {
			addBehaviour(new readyBehaviour(this, this.options));
			addBehaviour(new receiveOptimalPlan(this, this.truck, this.truckFilename, this.options));
		}
		
		else {
			addBehaviour(new noMapBehaviour(this, this.truck));
		}
		
	}
	
	protected void takeDown() {
		this.doDelete();
	}
	
	
	/**
	 * 
	 * @author ruivalentemaia
	 *
	 */
	class readyBehaviour extends SimpleBehaviour {
		private static final long serialVersionUID = 752338739127971004L;
		private boolean finished = false;
		private Options options;
		
		public Options getOptions() {
			return options;
		}

		public void setOptions(Options options) {
			this.options = options;
		}

		public readyBehaviour(Agent a, Options options){
			super(a);
			this.setOptions(options);
		}

		@Override
		public void action() {
			MessageTemplate m1 = MessageTemplate.MatchPerformative(ACLMessage.QUERY_IF);
			MessageTemplate m2 = MessageTemplate.MatchOntology("InitialMessage");
			MessageTemplate m3 = MessageTemplate.MatchContent("Ready?");
			
			MessageTemplate m1_and_m2 = MessageTemplate.and(m1, m2);
			MessageTemplate m12_and_m3 = MessageTemplate.and(m1_and_m2, m3);
			
			ACLMessage msg = receive(m12_and_m3);
			
			if(msg != null){
				if(this.options.isActiveConsolePrinting())
					System.out.println("Truck " + this.getAgent().getLocalName() + ": Received the " + msg.getContent() + " from " + msg.getSender().getLocalName() + ".");
				
				ACLMessage reply = msg.createReply();
				
				AID sender = msg.getSender();
				if(sender != null) reply.addReceiver(msg.getSender());
				
				String replyWith = msg.getReplyWith();
				if(replyWith != null) reply.setContent(replyWith);
				else reply.setContent("Yes. I'm ready.");
				
				if(this.options.isActiveConsolePrinting())
					System.out.println(getAID().getLocalName() + ": Sent the \"Yes. I'm ready.\" reply to " + msg.getSender().getLocalName() + ".");
				reply.setOntology("InitialMessage");
				
				send(reply);
				
				finished = true;
			}
			else {
				block();
			}
			
		}

		@Override
		public boolean done() {
			return finished;
		}
	}
	
	/**
	 * 
	 * @author ruivalentemaia
	 *
	 */
	class receiveOptimalPlan extends SimpleBehaviour {
		
		private static final long serialVersionUID = 6200150863055431193L;
		
		private boolean finished = false;
		private Truck truck;
		private String tempFilePath = System.getProperty("user.dir") + "/temp";
		private String filename;
		private int state;
		private Plan plan;
		private Options options;
		
		public receiveOptimalPlan(Agent a, Truck t, String filename, Options options){
			super(a);
			this.truck = t;
			this.filename = filename;
			this.state = 1;
			this.options = options;
		}

		public Truck getTruck() {
			return truck;
		}

		public void setTruck(Truck truck) {
			this.truck = truck;
		}
		
		public String getTempFilePath() {
			return tempFilePath;
		}

		public void setTempFilePath(String tempFilePath) {
			this.tempFilePath = tempFilePath;
		}
		
		public int getState() {
			return state;
		}

		public void setState(int state) {
			this.state = state;
		}

		public Plan getPlan() {
			return plan;
		}

		public void setPlan(Plan plan) {
			this.plan = plan;
		}

		/**
		 * 
		 * @param filename
		 * @return
		 * @throws ParserConfigurationException
		 * @throws SAXException
		 * @throws IOException
		 */
		public Plan importPlanFromXML(String filename) throws ParserConfigurationException, SAXException, IOException {
			File fXmlFile = new File(this.tempFilePath + "/" + filename);
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			Document doc = dBuilder.parse(fXmlFile);
			
			doc.getDocumentElement().normalize();
			
			String truck = doc.getElementsByTagName("truck").item(0).getTextContent();
			
			NodeList nList = doc.getElementsByTagName("assignment");
			HashMap<GarbageContainer, Double> map = new HashMap<GarbageContainer, Double>();
			HashMap<GarbageContainer, Boolean> cRegistry = new HashMap<GarbageContainer, Boolean>();
			for(int temp = 0; temp < nList.getLength(); temp++){
				Node nNode = nList.item(temp);
				
				if (nNode.getNodeType() == Node.ELEMENT_NODE) {
					Element eElement = (Element) nNode;
					
					Element gcElement = (Element) eElement.getElementsByTagName("garbageContainer").item(0);
					int gcId = Integer.parseInt(gcElement.getTextContent());
					
					Element amountToCollect = (Element) eElement.getElementsByTagName("amountToCollect").item(0);
					double amount = Double.parseDouble(amountToCollect.getTextContent());
					
					map.put(this.truck.getCompleteCityMap().selectGCFromId(gcId), amount);
					cRegistry.put(this.truck.getCompleteCityMap().selectGCFromId(gcId), false);
				}
			}
			
			return new Plan(this.truck.getCompleteCityMap().selectTruckFromName(truck), map, cRegistry);
		}
		
		
		public List<Goal> buildGoalsList(Plan p) {
			List<Goal> goals = new ArrayList<Goal>();
			List<GarbageContainer> garbageContainers = p.getAllGarbageContainers();
			
			//build a list of startPoints
			List<Point> startPoints = new ArrayList<Point>();
			startPoints.add(this.truck.getStartPosition());
			Iterator<GarbageContainer> gcIt = garbageContainers.iterator();
			while(gcIt.hasNext()){
				GarbageContainer gc = gcIt.next();
				Road gcRoad = this.truck.getCompleteCityMap().selectRoadFromGarbageContainer(gc.getPosition());
				startPoints.add(this.truck.getCompleteCityMap().selectPointFromRoad(gcRoad, gc.getPosition()));
			}
			
			//build a list of endPoints
			List<Point> endPoints = new ArrayList<Point>();
			gcIt = garbageContainers.iterator();
			while(gcIt.hasNext()){
				GarbageContainer gc = gcIt.next();
				Road gcRoad = this.truck.getCompleteCityMap().selectRoadFromGarbageContainer(gc.getPosition());
				endPoints.add(this.truck.getCompleteCityMap().selectPointFromRoad(gcRoad, gc.getPosition()));
			}
			endPoints.add(this.truck.getStartPosition());
			
			//build a list of Goals.
			Iterator<Point> itPoint = startPoints.iterator();
			int counter = 0;
			while(itPoint.hasNext()){
				Point startPoint = itPoint.next();
				Point endPoint = endPoints.get(counter);
				Goal g = new Goal(counter+1,startPoint, endPoint);
				Path path = new Path(counter+1);
				g.setBestPath(path);
				counter++;
				goals.add(g);
			}
			
			return goals;
		}
		
		
		public void exportTruckInformation(String filename, Truck t) throws ParserConfigurationException, TransformerException{
			/*
			 * private int id;
			 * private String truckName;
			 * private String garbageType;
			 * private Point startPosition;
			 * private Point currentPosition;
			 * private double currentOccupation;
			 * private double maxCapacity;
			 * private List<Point> pathWalked;
			 * private List<Point> pathToBeWalked;
			 * private List<Goal> goals;
			 * private List<GarbageContainer> garbageContainersToGoTo;
			 * //complete list of the CityMap points
			 * private CityMap completeCityMap;
			 * private Options options;
			 */
			
			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			Document doc = docBuilder.newDocument();
			
			Element rootElement = doc.createElement("truck");
			doc.appendChild(rootElement);
			
			Element id = doc.createElement("id");
			id.appendChild(doc.createTextNode(Integer.toString(t.getId())));
			rootElement.appendChild(id);
			
			Element truckName = doc.createElement("name");
			truckName.appendChild(doc.createTextNode(t.getTruckName()));
			rootElement.appendChild(truckName);
			
			Element garbageType = doc.createElement("garbageType");
			garbageType.appendChild(doc.createTextNode(t.getGarbageType()));
			rootElement.appendChild(garbageType);
			
			Element startPosition = doc.createElement("startPosition");
			startPosition.setAttribute("type", t.getStartPosition().getType());
			startPosition.setAttribute("x", Integer.toString(t.getStartPosition().getX()));
			startPosition.setAttribute("y", Integer.toString(t.getStartPosition().getY()));
			rootElement.appendChild(startPosition);
			
			Element currentPosition = doc.createElement("currentPosition");
			currentPosition.setAttribute("type", t.getCurrentPosition().getType());
			currentPosition.setAttribute("x", Integer.toString(t.getCurrentPosition().getX()));
			currentPosition.setAttribute("y", Integer.toString(t.getCurrentPosition().getY()));
			rootElement.appendChild(currentPosition);
			
			Element currentOccupation = doc.createElement("currentOccupation");
			currentOccupation.appendChild(doc.createTextNode(Double.toString(t.getCurrentOccupation())));
			rootElement.appendChild(currentOccupation);
			
			Element maxCapacity = doc.createElement("maxCapacity");
			maxCapacity.appendChild(doc.createTextNode(Double.toString(t.getMaxCapacity())));
			rootElement.appendChild(maxCapacity);
			
			if(t.getPathWalked().size() > 0) {
				Element pathWalked = doc.createElement("pathWalked");
				Iterator<Point> itPathWalked = t.getPathWalked().iterator();
				while(itPathWalked.hasNext()){
					Point p = itPathWalked.next();
					
					Element point = doc.createElement("pathWalkedPoint");
					point.setAttribute("x", Integer.toString(p.getX()));
					point.setAttribute("y", Integer.toString(p.getY()));
					pathWalked.appendChild(point);
				}
				rootElement.appendChild(pathWalked);
			}
			
			Element pathToBeWalked = doc.createElement("pathToBeWalked");
			Iterator<Point> itPathToBeWalked = t.getPathToBeWalked().iterator();
			while(itPathToBeWalked.hasNext()){
				Point p = itPathToBeWalked.next();
				
				Element point = doc.createElement("pathToBeWalkedPoint");
				point.setAttribute("x", Integer.toString(p.getX()));
				point.setAttribute("y", Integer.toString(p.getY()));
				pathToBeWalked.appendChild(point);
			}
			rootElement.appendChild(pathToBeWalked);
			
			Element goals = doc.createElement("goals");
			Iterator<Goal> itGoal = t.getGoals().iterator();
			while(itGoal.hasNext()){
				Goal g = itGoal.next();
				
				Element goal = doc.createElement("goal");
				
				Element goalId = doc.createElement("id");
				goalId.appendChild(doc.createTextNode(Integer.toString(g.getId())));
				goal.appendChild(goalId);
				
				Element startPoint = doc.createElement("startPoint");
				startPoint.setAttribute("x", Integer.toString(g.getStartPoint().getX()));
				startPoint.setAttribute("y", Integer.toString(g.getStartPoint().getY()));
				goal.appendChild(startPoint);
				
				Element endPoint = doc.createElement("endPoint");
				endPoint.setAttribute("x", Integer.toString(g.getEndPoint().getX()));
				endPoint.setAttribute("y", Integer.toString(g.getEndPoint().getY()));
				goal.appendChild(endPoint);
				
				Element bestPath = doc.createElement("bestPath");
				
				Element bestPathId = doc.createElement("id");
				bestPathId.appendChild(doc.createTextNode(Integer.toString(g.getBestPath().getId())));
				bestPath.appendChild(bestPathId);
				
				Element bestPathLength = doc.createElement("length");
				bestPathLength.appendChild(doc.createTextNode(Integer.toString(g.getBestPath().getLength())));
				bestPath.appendChild(bestPathLength);
				
				Element bestPathPoints = doc.createElement("points");
				
				Iterator<Point> itBPPoint = g.getBestPath().getPoints().iterator();
				while(itBPPoint.hasNext()){
					Point bpPoint = itBPPoint.next();
					
					Element bpPointElem = doc.createElement("point");
					bpPointElem.setAttribute("x", Integer.toString(bpPoint.getX()));
					bpPointElem.setAttribute("y", Integer.toString(bpPoint.getY()));
					bestPathPoints.appendChild(bpPointElem);
				}
				bestPath.appendChild(bestPathPoints);
				
				goal.appendChild(bestPath);
				
				goals.appendChild(goal);
			}
			rootElement.appendChild(goals);
			
			if(t.getGarbageContainersToGoTo().size() > 0) {
				Element garbageContainersToGo = doc.createElement("garbageContainersToGo");
				Iterator<GarbageContainer> itGC = t.getGarbageContainersToGoTo().iterator();
				while(itGC.hasNext()){
					GarbageContainer gc = itGC.next();
					
					Element garbageContainer = doc.createElement("garbageContainer");
					
					Element gcId = doc.createElement("id");
					gcId.appendChild(doc.createTextNode(Integer.toString(gc.getId())));
					garbageContainer.appendChild(gcId);
					
					Element gcType = doc.createElement("type");
					gcType.appendChild(doc.createTextNode(gc.getType()));
					garbageContainer.appendChild(gcType);
					
					Element gcCurrentOccupation = doc.createElement("currentOccupation");
					gcCurrentOccupation.appendChild(doc.createTextNode(Double.toString(gc.getCurrentOccupation())));
					garbageContainer.appendChild(gcCurrentOccupation);
					
					Element gcMaxCapacity = doc.createElement("maxCapacity");
					gcMaxCapacity.appendChild(doc.createTextNode(Double.toString(gc.getMaxCapacity())));
					garbageContainer.appendChild(gcMaxCapacity);
					
					Element gcPosition = doc.createElement("position");
					gcPosition.setAttribute("x", Integer.toString(gc.getPosition().getX()));
					gcPosition.setAttribute("y", Integer.toString(gc.getPosition().getY()));
					garbageContainer.appendChild(gcPosition);
					
					garbageContainersToGo.appendChild(garbageContainer);
				}
				rootElement.appendChild(garbageContainersToGo);
			}
			
			Element cityMap = doc.createElement("map");
			String cityMapFilename = t.getCompleteCityMap().getMapsFileName();
			if(cityMapFilename != null){
				cityMap.appendChild(doc.createTextNode(t.getCompleteCityMap().getMapsFileName()));
				rootElement.appendChild(cityMap);
			}
			
			Element options = doc.createElement("options");
			options.appendChild(doc.createTextNode(t.getOptions().getOptionsFile()));
			rootElement.appendChild(options);
			
			File f = new File(this.tempFilePath);
			f.setExecutable(true);
			f.setReadable(true);
			f.setWritable(true);
			File file = new File(f.toString() + "/" + filename);
			
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			Transformer transformer = transformerFactory.newTransformer();
			DOMSource source = new DOMSource(doc);
			StreamResult result = new StreamResult(file);
			
			transformer.transform(source, result);
		}
		

		@Override
		public void action() {
			
			switch(this.state){
			
			/*
			 * Receives Plan, processes again the goals of this Truck,
			 * exports its information to an XML file and goes to the next
			 * state.
			 */
			case 1:
				MessageTemplate m1 = MessageTemplate.MatchPerformative(ACLMessage.QUERY_IF);
				MessageTemplate m2 = MessageTemplate.MatchOntology("PlanOntology");
				
				MessageTemplate m1_and_m2 = MessageTemplate.and(m1, m2);
				
				ACLMessage msg = receive(m1_and_m2);
				
				if(msg != null){
					Plan plan = null;
					String filename = "";
					try {
						filename = msg.getContent();
						if(filename != "") plan = this.importPlanFromXML(filename);
					} catch (ParserConfigurationException | SAXException | IOException e) {
						e.printStackTrace();
					}
					
					if(plan != null){
						Truck t = null;
						try {
							t = new Truck(this.truck.getId(), this.truck.getTruckName(), this.truck.getGarbageType());
							t.setCompleteCityMap(this.truck.getCompleteCityMap());
							t.getCompleteCityMap().setMapsFileName(this.filename);
							t.setMaxCapacity(this.truck.getMaxCapacity());
						} catch (ParserConfigurationException | SAXException
								| IOException e) {
							e.printStackTrace();
						}
						
						if(t != null) {
							Point startingPoint = t.selectStartingPoint();
							t.setStartPosition(startingPoint);
							t.setCurrentPosition(startingPoint);
							t.setGoals(this.buildGoalsList(plan));
							t.setGarbageContainersToGoTo(plan.getAllGarbageContainers());
							this.plan = plan;
							t.buildTotalPathPlanning(2);
							
							this.truck = t;
							
							try {
								this.exportTruckInformation(this.filename, this.truck);
							} catch (ParserConfigurationException e) {
								e.printStackTrace();
							} catch (TransformerException e) {
								e.printStackTrace();
							}
							
							if(this.options.isActiveConsolePrinting())
								System.out.println(getAID().getLocalName() + ": Received and approved the optimal plan from " + msg.getSender().getLocalName() + ".");
							this.state = 2;
						}
					}
				}
				else{
					block();
				}
				break;
				
			/*
			 * Iterates over the pathToBeWalked list until it finds a 
			 * position that corresponds to a position where garbage
			 * should be collected.
			 */
			case 2:
				List<Goal> goals = this.truck.getGoals();
				Iterator<Point> pathToBeWalked = this.truck.getPathToBeWalked().iterator();
				
				while(pathToBeWalked.hasNext()){
					Point firstPoint = pathToBeWalked.next();
					pathToBeWalked.remove();
					
					this.truck.setCurrentPosition(firstPoint);
					this.truck.getPathWalked().add(firstPoint);
					this.truck.getCompleteCityMap().updateTruckPosition(this.truck);
					
					Iterator<Goal> itGoal = goals.iterator();
					while(itGoal.hasNext()){
						Goal g = itGoal.next();
						
						if(this.truck.positionClosestToGoal(firstPoint, g)){
							this.state = 3;
							break;
						}
					}
					if(this.options.isActiveConsolePrinting())
						System.out.println(getAID().getLocalName() + " : Moved to (" + firstPoint.getX() + ", " + firstPoint.getY() + ").");
					
					if(this.state == 3) break;
					
					this.state = 4;
					break;
				}
				
				//stop condition.
				if(this.truck.getPathToBeWalked().isEmpty()){
					this.state = 5;
				}
				break;
			
			/*
			 * Collects Garbage from a GarbageContainer.
			 */
			case 3:
				Iterator<Goal> itGoal = this.truck.getGoals().iterator();
				
				while(itGoal.hasNext()){
					Goal g = itGoal.next();
					HashMap<GarbageContainer, Double> assignment = this.plan.getAssignment();
					Iterator assignmentIt = assignment.entrySet().iterator();
					int counter = 1;
					
					while(assignmentIt.hasNext()){
						Map.Entry<GarbageContainer, Double> pairs = (Entry<GarbageContainer, Double>) assignmentIt.next();
						int truckX = this.truck.getCurrentPosition().getX();
						int truckY = this.truck.getCurrentPosition().getY();
						int gcPosX = pairs.getKey().getPosition().getX();
						int gcPosY = pairs.getKey().getPosition().getY();
						int diffPosX = Math.abs(gcPosX - g.getEndPoint().getX());
						int diffPosY = Math.abs(gcPosY - g.getEndPoint().getY());
						int diffTruckGCX = Math.abs(truckX - gcPosX);
						int diffTruckGCY = Math.abs(truckY - gcPosY);
						
						if( (truckX == gcPosX || truckX == gcPosX - 1 || truckX == gcPosX +1) &&
							(truckY == gcPosY || truckY == gcPosY - 1 || truckY == gcPosY + 1) &&
							(diffTruckGCX == 1 || diffTruckGCY == 1) && !(diffTruckGCX == 1 && diffTruckGCY == 1) &&
							(diffPosX == 1 || diffPosY == 1) && !(diffPosX == 1 && diffPosY == 1) ) {
							
							if(!this.plan.getCollectedRegistry().get(pairs.getKey())) {
								this.truck.collectGarbage(pairs.getKey(), pairs.getValue());
								this.plan.changeValueOfCollectRegistry(pairs.getKey());
								if(this.options.isActiveConsolePrinting())
									System.out.println(getAID().getLocalName() + ": Collected " + pairs.getValue() + " kg of garbage in (" + gcPosX + ", " + gcPosY + ").");
								break;
							}
						}
						counter++;
					}
					
					boolean allCollected = false;
					Iterator cRegistryIt = this.plan.getCollectedRegistry().entrySet().iterator();
					int size = this.plan.getCollectedRegistry().size();
					int counterTrue = 0;
					while(cRegistryIt.hasNext()){
						Map.Entry<GarbageContainer, Boolean> pairs = (Entry<GarbageContainer, Boolean>) cRegistryIt.next();
						if(pairs.getValue()){
							counterTrue++;
						}
					}
					
					if(counterTrue == size) allCollected = true;
					if(allCollected) break;
				}
				this.state = 4;
				break;
				
			/*
			 * Sends a message to PrinterAgent containing the updated information
			 * of the Truck.
			 */
			case 4:
				ACLMessage printMsg = new ACLMessage(ACLMessage.INFORM);
				printMsg.setOntology("Print");
				printMsg.setContent(this.truck.getId() + "," +
									this.truck.getCurrentPosition().getX() + "," +
									this.truck.getCurrentPosition().getY() + "," +
									this.truck.getGarbageType() + "," +
									this.truck.getCurrentOccupation() + "," +
									this.truck.getMaxCapacity());
				
				AMSAgentDescription [] agents = null;
		        try {
		            SearchConstraints c = new SearchConstraints();
		            c.setMaxResults ( new Long(-1) );
		            agents = AMSService.search( this.getAgent(), new AMSAgentDescription (), c );
		        }
		        catch (Exception e) { e.printStackTrace();}
				
		        String t = "printer";
		        for (int i=0; i<agents.length;i++) {
		            AID agentID = agents[i].getName();
		            if(agentID.getLocalName().equals(t)) {
		            	printMsg.addReceiver(agentID);
		            }
		        }
		        
		        send(printMsg);
				
		        if(this.options.isActiveConsolePrinting())
		        	System.out.println(getAID().getLocalName() + " : Sent information to printerAgent.");
		        this.state = 2;
		        
				break;
				
			default:
				if(this.options.isActiveConsolePrinting())
					System.out.println(getAID().getLocalName() + ": Finished working and I'm exiting.");
				this.finished = true;
				break;
			}
		}

		@Override
		public boolean done() {
			return this.finished;
		}
	}
	
	class noMapBehaviour extends SimpleBehaviour {

		private static final long serialVersionUID = -324071559784597197L;
		
		private boolean finished = false;
		private int state;
		private Truck truck;
		private HashMap<GarbageContainer, Double> garbageCollected;
		private CityMap unknownMap;
		private CityMap fullMap;
		private int counter;
		
		public boolean isFinished() {
			return finished;
		}

		public void setFinished(boolean finished) {
			this.finished = finished;
		}

		public int getState() {
			return state;
		}

		public void setState(int state) {
			this.state = state;
		}
		
		public Truck getTruck() {
			return truck;
		}

		public void setTruck(Truck truck) {
			this.truck = truck;
		}
		
		
		public noMapBehaviour(Agent a, Truck t) {
			super(a);
			
			this.truck = t;
			
			List<Goal> goals = new ArrayList<Goal>();
			this.truck.setGoals(goals);
			
			List<Point> pathToBeWalked = new ArrayList<Point>();
			this.truck.setPathToBeWalked(pathToBeWalked);
			
			List<GarbageContainer> garbageContainersToGoTo = new ArrayList<GarbageContainer>();
			this.truck.setGarbageContainersToGoTo(garbageContainersToGoTo);
			
			this.truck.setCurrentPosition(this.truck.getStartPosition());
			
			this.state = 1;
			this.garbageCollected = new HashMap<GarbageContainer, Double>();
			this.unknownMap = new CityMap();
			this.counter = 0;
			
			this.fullMap = this.truck.getCompleteCityMap();
			this.truck.setCompleteCityMap(unknownMap);
			this.truck.getCompleteCityMap().getPoints().add(this.truck.getCurrentPosition());
			
			//count how many GCs the TruckAgent is supposed to get to.
			List<GarbageContainer> gContainers = new CopyOnWriteArrayList<GarbageContainer> (this.fullMap.getGarbageContainers());
			synchronized(gContainers){
				Iterator<GarbageContainer> itGC = gContainers.iterator();
				while(itGC.hasNext()){
					GarbageContainer gc = itGC.next();
					if(gc.getType().equals(this.truck.getGarbageType()))
						this.counter++;
				}
			}
		}
		
		/**
		 * 
		 * @param cX
		 * @param pX
		 * @param nX
		 * @param cY
		 * @param pY
		 * @param nY
		 */
		public void collectGarbage(int cX, int pX, int nX, int cY, int pY, int nY){
			List<GarbageContainer> gContainers = new CopyOnWriteArrayList<GarbageContainer>(this.fullMap.getGarbageContainers());
			synchronized(gContainers){
				Iterator<GarbageContainer> itGContainer = gContainers.iterator();
				while(itGContainer.hasNext()){
					GarbageContainer gCont = itGContainer.next();
					int gContX = gCont.getPosition().getX();
					int gContY = gCont.getPosition().getY();
					
					if( ( (gContX == cX) || (gContX == pX) || (gContX == nX) ) &&
						( (gContY == cY) || (gContY == pY) || (gContY == nY)) ) {
						
						if(gCont.getType().equals(this.truck.getGarbageType())) {
					
							this.truck.getCompleteCityMap().getGarbageContainers().add(gCont);
							double currentOccupation = gCont.getCurrentOccupation();
							
							if( (currentOccupation <= (this.truck.getMaxCapacity() - this.truck.getCurrentOccupation())) &&
								(currentOccupation > 0)) {
								this.truck.setCurrentOccupation(this.truck.getCurrentOccupation() + currentOccupation);
								gCont.setCurrentOccupation(0);
								if(currentOccupation > 0) {
									this.garbageCollected.put(gCont, currentOccupation);
									System.out.println(getAID().getLocalName() + " : 1Collected " + currentOccupation 
											+ " kg of garbage in (" + gCont.getPosition().getX() 
											+ ", " + gCont.getPosition().getY() + ").");
								}
								else break;
							}
							
							else if((currentOccupation > (this.truck.getMaxCapacity() - this.truck.getCurrentOccupation())) &&
								(currentOccupation > 0)){
								double valueToTake = this.truck.getMaxCapacity() - this.truck.getCurrentOccupation();
								this.truck.setCurrentOccupation(this.truck.getCurrentOccupation() + valueToTake);
								gCont.setCurrentOccupation(gCont.getCurrentOccupation() - valueToTake);
								if(valueToTake > 0) {
									this.garbageCollected.put(gCont, valueToTake);
									System.out.println(getAID().getLocalName() + " : 2Collected " + valueToTake 
											+ " kg of garbage in (" + gCont.getPosition().getX() 
											+ ", " + gCont.getPosition().getY() + ").");
								}
								else break;
							}
							else break;
						}
					}
				}
			}
		}

		@Override
		public void action() {
			switch(this.state){
				/*
				 * TruckAgent is moving.
				 */
				case 1:
					/*
					 * stop condition: the number of GCs hit is equal to the number 
					 * of GCs that the TruckAgent is supposed to hit.
					 */
					while(garbageCollected.size() < this.counter){
						
						int currentX = this.truck.getCurrentPosition().getX();
						int currentY = this.truck.getCurrentPosition().getY();
						
						int previousX = currentX - 1;
						int nextX = currentX + 1;
						int previousY = currentY - 1;
						int nextY = currentY + 1;
						
						if(this.truck.getCurrentOccupation() == this.truck.getMaxCapacity()){
							Goal g = new Goal(this.truck.getGoals().size() + 1, 
											  this.truck.getCurrentPosition(),
											  this.truck.getStartPosition());
							this.truck.getGoals().add(g);
							
							g.setBestPath(new Path(1));
							this.truck.buildTotalPathPlanning(2);
						}
						
						if(this.truck.getGoals().size() > 0) {
							
							List<Point> pathToBeWalked = new CopyOnWriteArrayList<Point>(this.truck.getPathToBeWalked());
							synchronized(pathToBeWalked) {
								Iterator<Point> itPathToBeWalked = pathToBeWalked.iterator();
								while(itPathToBeWalked.hasNext()){
									Point nextPoint = itPathToBeWalked.next();
									String pointType = this.fullMap.getPointType(nextPoint);
									
									if(pointType != "") nextPoint.setType(pointType);
									else nextPoint.setType("ROAD");
									
									this.truck.getPathWalked().add(nextPoint);
									this.truck.setCurrentPosition(nextPoint);
									
									this.collectGarbage(currentX, previousX, nextX, currentY, previousY, nextY);
								}
							}
						}
						List<Point> nPathToBeWalked = new ArrayList<Point>();
						List<Goal> nGoals = new ArrayList<Goal>();
						this.truck.setPathToBeWalked(nPathToBeWalked);
						this.truck.setGoals(nGoals);
						
						if(this.getAgent().getQueueSize() > 0) {
							this.state = 2;
							break;
						}
						
						else {
							boolean msgSent = false;
							//checks if there is some GarbageContainer around this position.
							List<GarbageContainer> gContainers = new CopyOnWriteArrayList<GarbageContainer>(this.fullMap.getGarbageContainers());
							synchronized(gContainers){
								Iterator<GarbageContainer> itGContainer = gContainers.iterator();
								while(itGContainer.hasNext()){
									GarbageContainer gCont = itGContainer.next();
									int gContX = 0;
									gContX = gCont.getPosition().getX();
									int gContY = 0;
									gContY = gCont.getPosition().getY();
									
									if( ( (gContX == currentX) || (gContX == previousX) || (gContX == nextX) ) &&
										( (gContY == currentY) || (gContY == previousY) || (gContY == nextY)) ) {
										
										if(gCont.getType().equals(this.truck.getGarbageType())) {
									
											this.truck.getCompleteCityMap().getGarbageContainers().add(gCont);
											double currentOccupation = gCont.getCurrentOccupation();
											
											if( (currentOccupation <= (this.truck.getMaxCapacity() - this.truck.getCurrentOccupation())) &&
												(currentOccupation > 0)){
												this.truck.setCurrentOccupation(this.truck.getCurrentOccupation() + currentOccupation);
												gCont.setCurrentOccupation(0);
												if(currentOccupation > 0) {
													this.garbageCollected.put(gCont, currentOccupation);
													System.out.println(getAID().getLocalName() + " : 3Collected " + currentOccupation + " kg of garbage in (" + gCont.getPosition().getX() + ", " + gCont.getPosition().getY() + ").");
												}
												else break;
											}
											
											else if((currentOccupation > (this.truck.getMaxCapacity() - this.truck.getCurrentOccupation())) &&
													(currentOccupation > 0)){
												double valueToTake = this.truck.getMaxCapacity() - this.truck.getCurrentOccupation();
												this.truck.setCurrentOccupation(this.truck.getCurrentOccupation() + valueToTake);
												gCont.setCurrentOccupation(gCont.getCurrentOccupation() - valueToTake);
												if(valueToTake > 0){
													this.garbageCollected.put(gCont, valueToTake);
													System.out.println(getAID().getLocalName() + " : 4Collected " + valueToTake + " kg of garbage in (" + gCont.getPosition().getX() + ", " + gCont.getPosition().getY() + ").");
												}
												else break;
											}
											else break;
										}
										
										/*
										 * sends message to all Trucks of this GarbageType saying
										 * it found one.
										 */
										else {
											ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
											msg.setOntology("FoundGC");
											msg.setContent(gCont.getPosition().getX() 
												  + ", " + gCont.getPosition().getY() 
												  + "," + gCont.getCurrentOccupation()
												  + ", " + gCont.getMaxCapacity()
												  + "," + gCont.getType());
											
											AMSAgentDescription [] agents = null;
									        try {
									            SearchConstraints c = new SearchConstraints();
									            c.setMaxResults ( new Long(-1) );
									            agents = AMSService.search( this.getAgent(), new AMSAgentDescription (), c );
									        }
									        catch (Exception e) { e.printStackTrace();}
											
									        String t = getAID().getLocalName();
									        for (int i=0; i<agents.length;i++) {
									            AID agentID = agents[i].getName();
									            if(!agentID.getLocalName().equals(t)) {
									            	msg.addReceiver(agentID);
									            }
									        }
									        
									        send(msg);
									        
									        msgSent = true;
									        System.out.println(getAID().getLocalName() + " : Sent global message warning of GarbageContainer of type " + gCont.getType() + " at (" + gCont.getPosition().getX() + ", " + gCont.getPosition().getY() + ").");
										}
									}
									if(msgSent) break;
								}
							}
							
							/*
							 * Checks where to move.
							 */
							
							Point up = new Point(currentX, previousY);
							Point down = new Point(currentX, nextY);
							Point left = new Point(previousX, currentY);
							Point right = new Point(nextX, currentY);
							
							boolean isUpRoad = false;
							boolean isDownRoad = false;
							boolean isLeftRoad = false;
							boolean isRightRoad = false;
							
							if(this.fullMap.checkIfPointIsRoadOrCrossroads(up)) isUpRoad = true;
							if(this.fullMap.checkIfPointIsRoadOrCrossroads(down)) isDownRoad = true;
							if(this.fullMap.checkIfPointIsRoadOrCrossroads(left)) isLeftRoad = true;
							if(this.fullMap.checkIfPointIsRoadOrCrossroads(right)) isRightRoad = true;
							
							//the TruckAgent is on a CROSSROADS.
							if(isUpRoad && isDownRoad && isLeftRoad && isRightRoad){
								Random r = new Random();
								int val = r.nextInt(4-1) + 1;
								switch(val) {
									//move to the left of the map.
									case 1:
										if(!this.truck.hasPointBeenWalked(left)){
											this.truck.getPathWalked().add(new Point(currentX, currentY));
											this.truck.setCurrentPosition(left);
											System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
										}
										else val = 2;
										break;
									
									//move up on the map.
									case 2:
										if(!this.truck.hasPointBeenWalked(up)){
											this.truck.getPathWalked().add(new Point(currentX, currentY));
											this.truck.setCurrentPosition(up);
											System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
										}
										else val = 3;
										break;
									
									//move to the right of the map.
									case 3:
										if(!this.truck.hasPointBeenWalked(right)){
											this.truck.getPathWalked().add(new Point(currentX, currentY));
											this.truck.setCurrentPosition(right);
											System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
										}
										else val = 4;
										break;
									
									//move down on the map.
									case 4:
										if(!this.truck.hasPointBeenWalked(down)){
											this.truck.getPathWalked().add(new Point(currentX, currentY));
											this.truck.setCurrentPosition(down);
											System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
										}
										else val = 1;
										break;
									
									default:
										break;
								}
							}
							
							//the TruckAgent is somewhere where it can move up or down.
							else if(isUpRoad && isDownRoad){
								Random r = new Random();
								int val = r.nextInt(2-1) + 1;
								switch(val){
									case 1:
										if(!this.truck.hasPointBeenWalked(up)){
											this.truck.getPathWalked().add(new Point(currentX, currentY));
											this.truck.setCurrentPosition(up);
											System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
										}
										else val = 2;
										break;
									case 2:
										if(!this.truck.hasPointBeenWalked(down)){
											this.truck.getPathWalked().add(new Point(currentX, currentY));
											this.truck.setCurrentPosition(down);
											System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
										}
										else val = 1;
										break;
									default:
										break;
								}
							}
							//the TruckAgent is somewhere where it can move left or right.
							else if(isLeftRoad && isRightRoad){
								Random r = new Random();
								int val = r.nextInt(2-1) + 1;
								switch(val){
									case 1:
										if(!this.truck.hasPointBeenWalked(left)){
											this.truck.getPathWalked().add(new Point(currentX, currentY));
											this.truck.setCurrentPosition(left);
											System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
										}
										else val = 2;
										break;
									case 2:
										if(!this.truck.hasPointBeenWalked(right)){
											this.truck.getPathWalked().add(new Point(currentX, currentY));
											this.truck.setCurrentPosition(right);
											System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
										}
										else val = 1;
										break;
									default:
										break;
								}
							}
							//the TruckAgent is somewhere where it can only move up.
							else if(isUpRoad && !isDownRoad){
								if(!this.truck.hasPointBeenWalked(up)){
									this.truck.getPathWalked().add(new Point(currentX, currentY));
									this.truck.setCurrentPosition(up);
									System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
								}
							}
							//the TruckAgent is somewhere where it can only move down.
							else if(!isUpRoad && isDownRoad) {
								if(!this.truck.hasPointBeenWalked(down)){
									this.truck.getPathWalked().add(new Point(currentX, currentY));
									this.truck.setCurrentPosition(down);
									System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
								}
							}
							//the TruckAgent is somewhere where it can only move left.
							else if(isLeftRoad && !isRightRoad){
								if(!this.truck.hasPointBeenWalked(left)){
									this.truck.getPathWalked().add(new Point(currentX, currentY));
									this.truck.setCurrentPosition(left);
									System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
								}
							}
							//the TruckAgent is somewhere where it can only move right.
							else if(!isLeftRoad && isRightRoad){
								if(!this.truck.hasPointBeenWalked(right)){
									this.truck.getPathWalked().add(new Point(currentX, currentY));
									this.truck.setCurrentPosition(right);
									System.out.println(getAID().getLocalName() + " : Moved to (" + this.truck.getCurrentPosition().getX() + ", " + this.truck.getCurrentPosition().getY() + ").");
								}
							}
							else {
								System.out.println(getAID().getLocalName() + " : Something very strange happened. Going back to startPosition.");
								
								Goal g = new Goal(this.truck.getGoals().size() + 1, 
										  this.truck.getCurrentPosition(),
										  this.truck.getStartPosition());
								this.truck.getGoals().add(g);
						
								g.setBestPath(new Path(1));
								this.truck.buildTotalPathPlanning(2);
								
								List<Point> pathToBeWalked = new CopyOnWriteArrayList<Point>(this.truck.getPathToBeWalked());
								synchronized(pathToBeWalked) {
									Iterator<Point> itPathToBeWalked = pathToBeWalked.iterator();
									while(itPathToBeWalked.hasNext()){
										Point nextPoint = itPathToBeWalked.next();
										String pointType = this.fullMap.getPointType(nextPoint);
										
										if(pointType != "") nextPoint.setType(pointType);
										else nextPoint.setType("ROAD");
										
										this.truck.getPathWalked().add(nextPoint);
										this.truck.setCurrentPosition(nextPoint);
										
										this.collectGarbage(currentX, previousX, nextX, currentY, previousY, nextY);
									}
								}
							}
						}
					}
					
					if(this.counter == this.garbageCollected.size()){
						this.state = 3;
					}
					
					break;
				
				/*
				 * Receive message from another Truck saying they found a 
				 * GarbageContainer of this TruckAgent's garbageType.
				 */
				case 2:
					MessageTemplate m1 = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
					MessageTemplate m2 = MessageTemplate.MatchOntology("FoundGC");
					
					MessageTemplate m1_and_m2 = MessageTemplate.and(m1, m2);
					
					ACLMessage msg = receive(m1_and_m2);
					
					if(msg != null){
						String content = msg.getContent();
						String[] parts = content.split(",");
						
						/*
						 * gCont.getPosition().getX() 
						 * + ", " + gCont.getPosition().getY() 
						 * + "," + gCont.getCurrentOccupation() 
						 * + "," + gCont.getType())
						 */
						
						Point position = new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
						double cOccupation = Double.parseDouble(parts[2]);
						double maxCapacity = Double.parseDouble(parts[3]);
						String type = parts[3];
						int gcId = this.truck.getGarbageContainersToGoTo().size() + 1;
						
						if(type.equals(this.truck.getGarbageType())){
							GarbageContainer gc = new GarbageContainer(gcId, type, maxCapacity, cOccupation, position);
							this.truck.getGarbageContainersToGoTo().add(gc);
							
							int id = this.truck.getGoals().size() + 1;
							Goal g = new Goal(id, this.truck.getCurrentPosition(), position);
							g.setBestPath(new Path(id));
							
							this.truck.buildTotalPathPlanning(2);
						}
						
						System.out.print(getAID().getLocalName() + " : Received msg from " + msg.getSender() + ".");
						
						this.state = 1;
					}
					else block();
					break;
				
				case 3:
					System.out.println(getAID().getLocalName() + " : I'm finished and I'm leaving.");
					this.finished = true;
					this.state = 4;
					break;
				
				default:
					break;
			}
		}

		@Override
		public boolean done() {
			return finished;
		}
	}
}


