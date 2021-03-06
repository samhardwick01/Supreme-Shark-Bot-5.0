package executor;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import backend.Main;
import backend.Order;
import backend.OrderSettings;
import backend.Prompter;

public class Checkout {

	private Order order;
	private TaskProcessor processor;
	private HTTPConnector connector;
	private boolean errorPrompted = false;
	
	private HashMap<String, String> optionResponses = new HashMap<String, String>(); //store responses to disambiguation prompts, so they aren't asked twice

	private int attempts = 0;

	public Checkout(Order order, TaskProcessor processor, HTTPConnector connector) {
		this.order = order;
		this.processor = processor;
		this.connector = connector;
	}

	public void attemptCheckout() {
		
		try {

			prepCheckout();

			if (connector.checkoutPost()) processResponse(); //if we successfully posted the checkout form, process the server response to see
			
			if (Thread.currentThread().isInterrupted()) return;

		} catch (Exception e) {
			processor.printSys("Checkout error, retrying");
			e.printStackTrace();
		}
		attempts++;

		processor.setAllStatuses("Checkout (" + attempts + " attempts)");
		
	}

	private void prepCheckout() { //process form data by loading checkout page and seeing what it wants, set ordersettings post parameters accordingly

		String checkoutPage = connector.getHTMLString("https://www.supremenewyork.com/checkout", true);

		Document doc = Jsoup.parse(checkoutPage);

		System.out.println("Checkout page: " + checkoutPage.substring(0, 30));

		Elements form = doc.select("form input, select");  //form contains all inputs and all dropdowns

		String data = getFormPostProperties(form);

		processor.printSys("Checkout Data: " + data);
		order.getOrderSettings().setCheckoutParameters(data); //set data in settings, so the httpconnector can access it

		String checkoutLink = "https://www.supremenewyork.com" + doc.select("form").attr("action");
		processor.printSys("Checkout Post Link: " + checkoutLink);
		order.getOrderSettings().setCheckoutLink(checkoutLink); //set link in settings, so the httpconnector can access it
	}

	private String format(String name, String value) { //formats checkout params (name and value) into proper url encoded for for the post request
		String returnValue = null;

		try {
			returnValue = (URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace(); //this should never happen
		}

		return returnValue;
	}

	private String pullOptionValue(Element optionHolder, String expectedValue) {

		Elements options = optionHolder.select("option");
		
		String name = optionHolder.attr("name");

		System.out.println("New option " + name + " with expectedValue: " + expectedValue + " and options:");

		for (Element e : options) if (e.text().toLowerCase().equals(expectedValue.toLowerCase())) return e.attr("value"); //try and find the prompter option

		//no option matched the expectation:
		
		if (optionResponses.containsKey(name)) {
			processor.printSys("Option: " + name + " value inferred to be: " + optionResponses.get(name) + " based on prevoius prompt");
			return optionResponses.get(name); //if same field was already prompted, reuse that answer
		}
		
		String[] promptOptions = new String[options.size()];

		for (int i = 0; i < options.size(); i++) promptOptions[i] = options.get(i).text();

		int[] res = Prompter.comboPrompt("Which of these is the correct option for the checkout field '" + optionHolder.attr("name") + "' with expected value '" + expectedValue + "'?", "Checkout Field Confirmation", promptOptions, new String[]{"OK"});

		optionResponses.put(optionHolder.attr("name"), options.get(res[1]).attr("value")); //store this reponse in case this question must be asked again
		
		return options.get(res[1]).attr("value"); //return the inner text of the index of the selected option
	}

	private void processResponse() { //makes sure checkout was successful, if not prompt user or try again, depending on if supreme sent any errors

		System.out.println("Checkout Response:\n" + order.getOrderSettings().getCheckoutServerResponse());
		
		Document doc = Jsoup.parse(order.getOrderSettings().getCheckoutServerResponse());
		
		Elements errors = doc.select("div[class=errors]");
		
		if (!errors.isEmpty()) { //there are errors on the page
			if (!errorPrompted) {
				errorPrompted = true;
				int res = Prompter.buttonOptionPrompt("Order Settings need to be edited to fix the following errors:\n\t\t" + errors.text().replace(", ", ",\n\t\t"), "Checkout Errors, Abort?", new String[]{"Abort Bot", "Keep Trying"});
				System.out.println("Prompt reponse: " + res);
				if (res == 0) Main.getGUI().abort(); //if they chose abort, abort
			} else {
				processor.print("Supreme returned the checkout errors:\n\t" + errors.text().replace(", ", ",\n\t"));
			}
			
			return; //there are errors, checkout wasn't successful
			
		}
		
		Elements orderConfirmation = doc.select("div[id=confirmation]");
		
		if (!orderConfirmation.isEmpty()) {
			String orderNumber = orderConfirmation.select("strong").last().text();
			processor.setAllStatuses("Order: " + orderNumber);
			processor.print("Checkout Successful, Order: " + orderNumber);
			Thread.currentThread().interrupt();
			return;
		}
		
		processor.print("Checkout unsuccessful, retrying");
		return; //try again
		
	}

	private String getFormPostProperties(Elements form) {

		ArrayList<String> postKeys = new ArrayList<String>();
		ArrayList<String> postValues = new ArrayList<String>();

		OrderSettings settings = order.getOrderSettings();

		System.out.println("Store: " + settings.getStore());

		processor.printSys("Number of elements in checkout form: " + form.size());
		
		for (int i = 0; i < form.size(); i++) {
			System.out.println("Form member number " + i + " with name " + form.get(i).attr("name") + " and given value: " + form.get(i).attr("value"));
		}

		for (int i = 0; i < form.size(); i ++) {

			Element current = form.get(i);

			String givenValue = current.attr("value"); 

			String setTo = "";

			if (settings.getStore().equals("US/CANADA")) {

				if (i == 0) {
					setTo = givenValue;
				} else if (i == 1) {
					setTo = givenValue;
				} else if (i == 2) {
					setTo = settings.getName();
				} else if (i == 3) {
					setTo = settings.getEmail();
				} else if (i == 4) {
					setTo = settings.getPhone(); //need formatting
				} else if (i == 5) {
					setTo = settings.getAddress1();
				} else if (i == 6) {
					setTo = settings.getAddress2();
				} else if (i == 7) {
					setTo = settings.getPostalCode();
				} else if (i == 8) {
					setTo = settings.getCity();
				} else if (i == 9) {
					setTo = pullOptionValue(current, settings.getStateAbbr());
				} else if (i == 10) {
					setTo = pullOptionValue(current, settings.getCountry());
				} else if (i == 11) {
					setTo = givenValue;
				} else if (i == 12) {
					setTo = ""; //store credit id
				} else if (i == 13) {
					setTo = "AVOID_STRING";
				} else if (i == 14) {
					setTo = pullOptionValue(current, settings.getCcProvider());
				} else if (i == 15) {
					setTo = settings.getCcNumber();
				} else if (i == 16) {
					setTo = pullOptionValue(current, settings.getExpMonth());
				} else if (i == 17) {
					setTo = pullOptionValue(current, settings.getExpYear());
				} else if (i == 18) {
					setTo = settings.getCvv();
				} else if (i == 19) {
					setTo = givenValue;
				} else if (i == 20) {
					setTo = givenValue;
				} else if (i == 21) {
					setTo = "";
				} else if (i == 22) {
					setTo = "AVOID_STRING";
				} 

			} else if (settings.getStore().equals("UK")) {
				
				if (i == 0) {
					setTo = givenValue;
				} else if (i == 1) {
					setTo = givenValue;
				} else if (i == 2) {
					setTo = settings.getName();
				} else if (i == 3) {
					setTo = settings.getEmail();
				} else if (i == 4) {
					setTo = settings.getPhoneUnformatted(); //no formatting for UK
				} else if (i == 5) {
					setTo = settings.getAddress1();
				} else if (i == 6) {
					setTo = settings.getAddress2();
				} else if (i == 7) {
					setTo = settings.getAddress3();
				} else if (i == 8) {
					setTo = settings.getCity();
				} else if (i == 9) {
					setTo = settings.getPostalCode();
				} else if (i == 10) {
					setTo = pullOptionValue(current, settings.getCountry());
				} else if (i == 11) {
					setTo = givenValue;
				} else if (i == 12) {
					setTo = ""; //store credit id
				} else if (i == 13) {
					setTo = "AVOID_STRING";
				} else if (i == 14) {
					setTo = pullOptionValue(current, settings.getCcProvider());
				} else if (i == 15) {
					setTo = settings.getCcNumber();
				} else if (i == 16) {
					setTo = pullOptionValue(current, settings.getExpMonth());
				} else if (i == 17) {
					setTo = pullOptionValue(current, settings.getExpYear());
				} else if (i == 18) {
					setTo = settings.getCvv();
				} else if (i == 19) {
					setTo = givenValue;
				} else if (i == 20) {
					setTo = givenValue;
				} else if (i == 21) {
					setTo = "";
				} else if (i == 22) {
					setTo = "AVOID_STRING";
				} 

				
			} else { //jp
				
				if (i == 0) {
					setTo = givenValue;
				} else if (i == 1) {
					setTo = givenValue;
				} else if (i == 2) {
					try {
						setTo = settings.getName().split(" ")[1]; //last name
					} catch (Exception e) {
						setTo = settings.getName();
					}
				} else if (i == 3) {
					try {
						setTo = settings.getName().split(" ")[0]; //last name
					} catch (Exception e) {
						setTo = settings.getName();
					}
				} else if (i == 4) {
					setTo = settings.getEmail();
				} else if (i == 5) {
					setTo = settings.getPhoneUnformatted(); //no formatting for jp
				} else if (i == 6) {
					setTo = pullOptionValue(current, settings.getCountry());
				} else if (i == 7) {
					setTo = settings.getCity();
				} else if (i == 8) {
					setTo = settings.getAddress1();
				} else if (i == 9) {
					setTo = settings.getPostalCode();
				} else if (i == 10) {
					setTo = givenValue;
				} else if (i == 11) {
					setTo = "AVOID_STRING";
				} else if (i == 12) {
					setTo = pullOptionValue(current, settings.getCcProvider());
				} else if (i == 13) {
					setTo = settings.getCcNumber();
				} else if (i == 14) {
					setTo = settings.getExpMonth();
				} else if (i == 15) {
					setTo = settings.getExpYear();
				} else if (i == 16) {
					setTo = settings.getCvv();
				} else if (i == 17) {
					setTo = givenValue;
				} else if (i == 18) {
					setTo = givenValue;
				} else if (i == 19) {
					setTo = "";
				} else if (i == 20) {
					setTo = "AVOID_STRING";
				}
				
				
			}

			if (setTo.isEmpty()) setTo = ""; //no null

			if (!setTo.equals("AVOID_STRING")) {
				postKeys.add(current.attr("name")); //set the key to be posted to the form to the element name
				postValues.add(setTo); //set the value of the key to setTo
			}

		}

		String data = "";

		for (int i = 0; i < postKeys.size(); i++) {
			if (i != 0) data += "&";
			String formattedValue = format(postKeys.get(i), postValues.get(i));
			data += formattedValue;
			processor.printSys("New Formatted Checkout Data: " + formattedValue);
		}

		return data;

	}

}