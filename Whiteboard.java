package project;


import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List; // Explicit import
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

// Interface for drawable shapes
interface Drawable extends Serializable {
    void draw(Graphics2D g2, int startX, int startY, int endX, int endY, Color color, int brushSize);
}

// Abstract class for shapes (No changes needed)
abstract class AbstractShape implements Drawable {
    protected int startX, startY, endX, endY;
    protected Color color;
    protected int brushSize;

    public void setProperties(int startX, int startY, int endX, int endY, Color color, int brushSize) {
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.color = color;
        this.brushSize = brushSize;
    }
}

// Line shape implementation (No changes needed)
class LineShape extends AbstractShape {
    @Override
    public void draw(Graphics2D g2, int startX, int startY, int endX, int endY, Color color, int brushSize) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(brushSize));
        g2.drawLine(startX, startY, endX, endY);
    }
}

// Rectangle shape implementation (No changes needed)
class RectangleShape extends AbstractShape {
    @Override
    public void draw(Graphics2D g2, int startX, int startY, int endX, int endY, Color color, int brushSize) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(brushSize));
        g2.drawRect(Math.min(startX, endX), Math.min(startY, endY),
                Math.abs(endX - startX), Math.abs(endY - startY));
    }
}

// Drawing action class for network transmission
class DrawingAction implements Serializable {
    private static final long serialVersionUID = 1L;
    String type; // "draw", "clear", "bgColor"
    String shapeType; // "Free Draw", "Line", "Rectangle", null for non-draw actions
    int startX, startY, endX, endY;
    Color color;
    int brushSize;
    String username;
    String tabId; // ID (title) of the tab this action applies to

    public DrawingAction(String type, String shapeType, int startX, int startY, int endX, int endY,
                         Color color, int brushSize, String username, String tabId) {
        this.type = type;
        this.shapeType = shapeType;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.color = color;
        this.brushSize = brushSize;
        this.username = username;
        this.tabId = tabId; // Assign tab ID
    }
}

// Class to send initial canvas state
class StateUpdate implements Serializable {
    private static final long serialVersionUID = 3L;
    String tabId;
    byte[] imageData; // Send image data as bytes

    public StateUpdate(String tabId, byte[] imageData) {
        this.tabId = tabId;
        this.imageData = imageData;
    }
}


// Chat message class (No changes needed)
class ChatMessage implements Serializable {
    private static final long serialVersionUID = 2L; // Changed to 2 to avoid potential conflict if reusing old serialization
    String username;
    String message;

    public ChatMessage(String username, String message) {
        this.username = username;
        this.message = message;
    }
}

// Client handler class to store client connections
class ClientHandler {
    Socket socket;
    ObjectOutputStream out;
    ObjectInputStream in;
    String username;

    public ClientHandler(Socket socket, ObjectOutputStream out, ObjectInputStream in, String username) {
        this.socket = socket;
        this.out = out;
        this.in = in;
        this.username = username;
    }
}

// Whiteboard application
public class Whiteboard extends JFrame {
    private static final long serialVersionUID = 1L;
    private JTabbedPane tabbedPane;
    private String username;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    // Use ConcurrentHashMap for thread safety when accessing from network threads and GUI thread
    private Map<String, CanvasPanel> canvasMap = new ConcurrentHashMap<>();
    // Use synchronized set for thread safety
    private Set<String> collaborators = Collections.synchronizedSet(new HashSet<>());
    // Use synchronized list for thread safety
    private List<ClientHandler> clientHandlers = Collections.synchronizedList(new ArrayList<>());
    private JTextArea chatArea;
    private JTextField chatInput;
    private JList<String> collaboratorList;
    private DefaultListModel<String> collaboratorListModel;
    private boolean isServerMode;

    // Toolbar components needed for enabling/disabling
    private JButton clearButton, colorButton, bgColorButton, eraserButton, undoButton, redoButton, saveButton, closeTabButton;
    private JComboBox<String> brushSizeBox, shapeBox;

    public Whiteboard(boolean isServer, String host, int port, String username) {
        this.username = username;
        this.isServerMode = isServer;
        setTitle("Collaborative Whiteboard - " + username + (isServer ? " (Server)" : " (Client)"));
        setSize(1200, 800);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Initialize tabbed pane
        tabbedPane = new JTabbedPane();
        // Add initial tab *after* network setup potentially receives state
        // addNewTab("Main Canvas"); // Moved this call

        // Initialize toolbar
        JPanel toolbar = createToolbar();
        add(toolbar, BorderLayout.NORTH);

        // Initialize chat and collaborator panel
        JPanel eastPanel = createEastPanel();
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabbedPane, eastPanel);
        splitPane.setResizeWeight(1.0); // Give canvas area priority when resizing
        splitPane.setDividerLocation(900); // Initial divider location
        add(splitPane, BorderLayout.CENTER);

        // Add listener to enable/disable controls based on selected tab
        tabbedPane.addChangeListener(e -> updateToolbarState());

        // Add window listener for cleanup
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cleanup();
            }
        });

        // Setup networking *before* creating the first tab if client (to receive state)
        setupNetworking(isServer, host, port);

        // If server, or if client connection failed, create the first tab now.
        // Client connection thread will handle creating tabs if state is received.
        if (isServerMode || clientSocket == null || !clientSocket.isConnected()) {
            if (canvasMap.isEmpty()) { // Avoid adding if network already added it
                 addNewTab("Main Canvas");
            }
        }
         updateToolbarState(); // Initial state

        setVisible(true);
        System.out.println("Whiteboard Frame Initialized and Visible.");
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT)); // Align left

        JButton newTabButton = new JButton("New Tab");
        clearButton = new JButton("Clear");
        colorButton = new JButton("Color");
        bgColorButton = new JButton("Background");
        eraserButton = new JButton("Eraser");
        undoButton = new JButton("Undo"); // Note: Undo/Redo is local only
        redoButton = new JButton("Redo"); // Note: Undo/Redo is local only
        saveButton = new JButton("Save");
        closeTabButton = new JButton("Close Tab");

        brushSizeBox = new JComboBox<>(new String[]{"Small", "Medium", "Large"});
        brushSizeBox.setSelectedItem("Medium"); // Default
        shapeBox = new JComboBox<>(new String[]{"Free Draw", "Line", "Rectangle"});

        toolbar.add(newTabButton);
        toolbar.add(clearButton);
        toolbar.add(colorButton);
        toolbar.add(bgColorButton);
        toolbar.add(eraserButton);
        toolbar.add(undoButton);
        toolbar.add(redoButton);
        toolbar.add(closeTabButton);
        toolbar.add(new JLabel("Brush:"));
        toolbar.add(brushSizeBox);
        toolbar.add(new JLabel("Shape:"));
        toolbar.add(shapeBox);
        toolbar.add(saveButton);

        // Action listeners
        newTabButton.addActionListener(e -> {
            String tabName = JOptionPane.showInputDialog(this, "Enter tab name:", "New Tab", JOptionPane.PLAIN_MESSAGE);
            if (tabName != null && !tabName.trim().isEmpty() && !canvasMap.containsKey(tabName.trim())) {
                addNewTab(tabName.trim());
                // Optional: Send notification about new tab? (Not implemented here)
            } else if (tabName != null && canvasMap.containsKey(tabName.trim())) {
                 JOptionPane.showMessageDialog(this, "A tab with that name already exists.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        clearButton.addActionListener(e -> {
            CanvasPanel current = getCurrentCanvas();
            if (current != null) current.clear();
        });
        colorButton.addActionListener(e -> {
            CanvasPanel current = getCurrentCanvas();
            if (current != null) current.pickColor();
        });
        bgColorButton.addActionListener(e -> {
            CanvasPanel current = getCurrentCanvas();
            if (current != null) current.pickBackgroundColor();
        });
        eraserButton.addActionListener(e -> {
            CanvasPanel current = getCurrentCanvas();
            if (current != null) current.useEraser();
        });
        undoButton.addActionListener(e -> {
            CanvasPanel current = getCurrentCanvas();
            if (current != null) current.undo();
        });
        redoButton.addActionListener(e -> {
            CanvasPanel current = getCurrentCanvas();
            if (current != null) current.redo();
        });
        brushSizeBox.addActionListener(e -> {
            CanvasPanel current = getCurrentCanvas();
            if (current != null) current.setBrushSize((String) brushSizeBox.getSelectedItem());
        });
        shapeBox.addActionListener(e -> {
            CanvasPanel current = getCurrentCanvas();
            if (current != null) current.setShape((String) shapeBox.getSelectedItem());
        });
        saveButton.addActionListener(e -> {
            CanvasPanel current = getCurrentCanvas();
            if (current != null) current.saveImage();
        });
        closeTabButton.addActionListener(e -> closeCurrentTab());

        return toolbar;
    }

    private JPanel createEastPanel() {
        JPanel eastPanel = new JPanel(new BorderLayout());
        eastPanel.setPreferredSize(new Dimension(250, getHeight())); // Adjusted size

        // Collaborators list
        collaboratorListModel = new DefaultListModel<>();
        collaboratorList = new JList<>(collaboratorListModel);
        JScrollPane collaboratorScroll = new JScrollPane(collaboratorList);
        collaboratorScroll.setBorder(BorderFactory.createTitledBorder("Collaborators"));
        collaboratorScroll.setPreferredSize(new Dimension(250, 150)); // Fixed height

        // Chat area
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true); // Wrap lines
        chatArea.setWrapStyleWord(true); // Wrap by word
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(BorderFactory.createTitledBorder("Chat"));
        // Preferred size will be managed by BorderLayout

        // Chat input
        chatInput = new JTextField();
        chatInput.addActionListener(e -> {
            String message = chatInput.getText().trim();
            if (!message.isEmpty()) {
                sendChatMessage(message);
                chatInput.setText("");
            }
        });
        JPanel chatInputPanel = new JPanel(new BorderLayout());
        chatInputPanel.add(chatInput, BorderLayout.CENTER);
        JButton sendButton = new JButton("Send");
        sendButton.addActionListener(e -> {
             String message = chatInput.getText().trim();
            if (!message.isEmpty()) {
                sendChatMessage(message);
                chatInput.setText("");
            }
        });
        chatInputPanel.add(sendButton, BorderLayout.EAST);


        eastPanel.add(collaboratorScroll, BorderLayout.NORTH);
        eastPanel.add(chatScroll, BorderLayout.CENTER);
        eastPanel.add(chatInputPanel, BorderLayout.SOUTH);

        return eastPanel;
    }

    // Adds a new tab and its corresponding canvas panel
    private void addNewTab(String title) {
        SwingUtilities.invokeLater(() -> {
            if (!canvasMap.containsKey(title)) {
                System.out.println("Adding new tab: " + title);
                CanvasPanel newCanvas = new CanvasPanel(this, title); // Pass whiteboard ref and title
                canvasMap.put(title, newCanvas);
                tabbedPane.addTab(title, newCanvas);
                tabbedPane.setSelectedComponent(newCanvas);
                updateToolbarState(); // Enable controls for the new tab
            } else {
                System.out.println("Tab already exists: " + title);
                // Optionally switch to the existing tab
                tabbedPane.setSelectedComponent(canvasMap.get(title));
            }
        });
    }


    // Safely gets the current canvas panel
    private CanvasPanel getCurrentCanvas() {
         Component selected = tabbedPane.getSelectedComponent();
         if (selected instanceof CanvasPanel) {
             return (CanvasPanel) selected;
         }
         return null; // No tab selected or selected component is not a CanvasPanel
    }

    // Gets the ID (title) of the currently selected tab
    private String getCurrentTabId() {
        int selectedIndex = tabbedPane.getSelectedIndex();
        if (selectedIndex != -1) {
            return tabbedPane.getTitleAt(selectedIndex);
        }
        return null; // No tab selected
    }

    private void closeCurrentTab() {
        int selectedIndex = tabbedPane.getSelectedIndex();
        if (selectedIndex != -1) {
             String title = tabbedPane.getTitleAt(selectedIndex);
            if (tabbedPane.getTabCount() > 1) {
                int confirm = JOptionPane.showConfirmDialog(this,
                        "Are you sure you want to close the tab '" + title + "'?",
                        "Confirm Close",
                        JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    canvasMap.remove(title);
                    tabbedPane.removeTabAt(selectedIndex);
                     updateToolbarState(); // Update state after closing
                     // Optional: Send notification about closed tab? (Not implemented)
                }
            } else {
                JOptionPane.showMessageDialog(this,
                        "Cannot close the last tab.",
                        "Warning",
                        JOptionPane.WARNING_MESSAGE);
            }
        }
    }

     // Enable/disable toolbar controls based on whether a tab is selected
    private void updateToolbarState() {
        boolean enabled = (tabbedPane.getSelectedIndex() != -1);
        clearButton.setEnabled(enabled);
        colorButton.setEnabled(enabled);
        bgColorButton.setEnabled(enabled);
        eraserButton.setEnabled(enabled);
        undoButton.setEnabled(enabled);
        redoButton.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        brushSizeBox.setEnabled(enabled);
        shapeBox.setEnabled(enabled);
        closeTabButton.setEnabled(enabled && tabbedPane.getTabCount() > 1);
    }


    private void setupNetworking(boolean isServer, String host, int port) {
        collaborators.add(username); // Add self immediately
        updateCollaboratorList();

        if (isServer) {
            new Thread(() -> startServer(port), "Server-Thread").start();
        } else {
            new Thread(() -> connectToServer(host, port), "Client-Thread").start();
        }
    }

    private void startServer(int port) {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Server started on port " + port);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Server started on port " + port, "Server Info", JOptionPane.INFORMATION_MESSAGE));

            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSoc = serverSocket.accept();
                    System.out.println("Client connected: " + clientSoc.getInetAddress());
                    new Thread(() -> handleClient(clientSoc), "ClientHandler-Thread-" + clientSoc.getPort()).start();
                } catch (IOException e) {
                    if (serverSocket.isClosed()) {
                        System.out.println("Server socket closed, stopping accept loop.");
                        break; // Exit loop cleanly if socket is closed
                    }
                    System.err.println("Error accepting client connection: " + e.getMessage());
                    // e.printStackTrace(); // Potentially too noisy
                }
            }
        } catch (IOException e) {
            final String errorMsg = "Server error: " + e.getMessage();
            System.err.println(errorMsg);
            e.printStackTrace();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, errorMsg, "Server Error", JOptionPane.ERROR_MESSAGE));
        } finally {
             System.out.println("Server shutdown complete.");
        }
    }

    private void handleClient(Socket socket) {
        ObjectOutputStream clientOut = null;
        ObjectInputStream clientIn = null;
        String clientUsername = null;
        ClientHandler handler = null;

        try {
            // 1. Create output stream FIRST and flush header
            clientOut = new ObjectOutputStream(socket.getOutputStream());
            clientOut.flush(); // Essential for preventing deadlock!
            System.out.println("Server: Output stream created for " + socket.getInetAddress());

            // 2. Create input stream
            clientIn = new ObjectInputStream(socket.getInputStream());
            System.out.println("Server: Input stream created for " + socket.getInetAddress());

            // 3. Read username from client
            clientUsername = (String) clientIn.readObject();
            System.out.println("Server: Received username: " + clientUsername);

            // Check for duplicate username 
            synchronized(collaborators) {
                if (collaborators.contains(clientUsername)) {
                    // Handle duplicate: Send error and close? Or assign a new name?
                    System.err.println("Duplicate username attempt: " + clientUsername);
                    clientOut.writeObject(new ChatMessage("Server", "ERROR: Username '" + clientUsername + "' is already taken."));
                    clientOut.flush();
                    return; // Close connection for this handler
                }
            }


            // 4. Create and store client handler
            handler = new ClientHandler(socket, clientOut, clientIn, clientUsername);
            clientHandlers.add(handler);

            // 5. Update collaborator lists and broadcast
            collaborators.add(clientUsername);
            updateCollaboratorList(); // Update server's own list
            broadcastCollaborators(); // Send updated list to everyone

            // 6. Send current canvas states to the *new* client
            sendCurrentStateToClient(handler);

            // 7. Send welcome message to everyone
            ChatMessage welcomeMsg = new ChatMessage("Server", clientUsername + " has joined the whiteboard.");
            appendChatMessage(welcomeMsg); // Add to server's chat
            broadcastChatMessage(welcomeMsg, null); // Send to all clients

             System.out.println("Server: Client " + clientUsername + " fully connected and initialized.");


            // 8. Enter listening loop for messages from this client
            while (!socket.isClosed()) {
                 try {
                     Object obj = clientIn.readObject();

                     if (obj instanceof DrawingAction) {
                         DrawingAction action = (DrawingAction) obj;
                         System.out.println("Server: Received DrawingAction from " + action.username + " for tab " + action.tabId);
                         // Process locally (on server's GUI)
                         SwingUtilities.invokeLater(() -> processDrawingAction(action));
                         // Broadcast to other clients
                         broadcastAction(action, clientOut);
                     } else if (obj instanceof ChatMessage) {
                         ChatMessage msg = (ChatMessage) obj;
                          System.out.println("Server: Received ChatMessage from " + msg.username);
                         // Display locally
                         SwingUtilities.invokeLater(() -> appendChatMessage(msg));
                         // Broadcast to other clients
                         broadcastChatMessage(msg, clientOut);
                     } else {
                          System.out.println("Server: Received unknown object type from " + clientUsername);
                     }
                 } catch (EOFException e) {
                      System.out.println("Server: Client " + clientUsername + " reached end of stream (disconnected).");
                      break; // Client closed connection cleanly
                 } catch (SocketException e) {
                     System.out.println("Server: Socket exception for client " + clientUsername + " (likely disconnected): " + e.getMessage());
                      break;
                 } catch (IOException | ClassNotFoundException e) {
                     System.err.println("Server: Error reading from client " + clientUsername + ": " + e.getMessage());
                     // e.printStackTrace();
                     break; // Assume client disconnected on error
                 }
            }
        } catch (Exception e) { // Catch broader exceptions during setup
            System.err.println("Server: Error during initial handling for client " + (clientUsername != null ? clientUsername : socket.getInetAddress()) + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup: Remove client, notify others, close socket
             if (handler != null) {
                  clientHandlers.remove(handler);
                  if (clientUsername != null) {
                      collaborators.remove(clientUsername);
                      System.out.println("Server: Client " + clientUsername + " disconnected.");
                      ChatMessage leaveMsg = new ChatMessage("Server", clientUsername + " has left the whiteboard.");
                      appendChatMessage(leaveMsg);
                      broadcastChatMessage(leaveMsg, null); // Notify remaining clients
                      broadcastCollaborators(); // Update collaborator lists for everyone
                      updateCollaboratorList(); // Update server's list
                  }
             }
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                System.err.println("Server: Error closing client socket: " + e.getMessage());
            }
            System.out.println("Server: Cleaned up resources for a client connection.");
        }
    }

    // Sends the current image state of all canvases to a specific client
    private void sendCurrentStateToClient(ClientHandler targetHandler) {
        System.out.println("Server: Sending initial state to " + targetHandler.username);
        for (Map.Entry<String, CanvasPanel> entry : canvasMap.entrySet()) {
            String tabId = entry.getKey();
            CanvasPanel canvas = entry.getValue();
            BufferedImage img = canvas.getImage(); // Get the current image

            if (img != null) {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(img, "png", baos); // Write image as PNG to byte array
                    byte[] imageData = baos.toByteArray();

                    StateUpdate update = new StateUpdate(tabId, imageData);
                    targetHandler.out.writeObject(update);
                    targetHandler.out.flush();
                    System.out.println("Server: Sent state for tab '" + tabId + "' (" + imageData.length + " bytes) to " + targetHandler.username);

                } catch (IOException e) {
                    System.err.println("Server: Failed to send state for tab '" + tabId + "' to " + targetHandler.username + ": " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                 System.out.println("Server: Skipping state for tab '" + tabId + "' (image is null)");
            }
        }
         System.out.println("Server: Finished sending initial state to " + targetHandler.username);
    }


    private void connectToServer(String host, int port) {
        try {
            System.out.println("Client: Attempting to connect to " + host + ":" + port + " as " + username);
            clientSocket = new Socket(host, port);
            System.out.println("Client: Socket connection established.");

            
            out = new ObjectOutputStream(clientSocket.getOutputStream());
            out.flush();
            System.out.println("Client: Output stream created.");

            
            in = new ObjectInputStream(clientSocket.getInputStream());
            System.out.println("Client: Input stream created.");

            
            out.writeObject(username);
            out.flush();
            System.out.println("Client: Sent username '" + username + "' to server.");

            SwingUtilities.invokeLater(() -> appendChatMessage(new ChatMessage("System", "Connected to server successfully!")));


            while (!clientSocket.isClosed()) {
                try {System.out.println("Client: Waiting to receive object from server...");
                Object obj = in.readObject();
                System.out.println("Client: Received object: " + obj.getClass().getSimpleName());

                    if (obj instanceof DrawingAction) {
                        DrawingAction action = (DrawingAction) obj;
                        System.out.println("Client: Received DrawingAction for tab " + action.tabId);
                        SwingUtilities.invokeLater(() -> processDrawingAction(action));
                    } else if (obj instanceof ChatMessage) {
                        ChatMessage msg = (ChatMessage) obj;
                        
                        if (msg.username.equals("Server") && msg.message.startsWith("ERROR:")) {
                             System.err.println("Client: Received error from server: " + msg.message);
                             JOptionPane.showMessageDialog(this, msg.message, "Server Error", JOptionPane.ERROR_MESSAGE);
                            
                             if (msg.message.contains("Username")) {
                                 cleanup(); // Close socket etc.
                                 // Maybe disable GUI elements
                                 return; 
                             }
                        } else {
                            System.out.println("Client: Received ChatMessage from " + msg.username);
                            SwingUtilities.invokeLater(() -> appendChatMessage(msg));
                        }
                    } else if (obj instanceof Set) { // Receiving collaborator list update
                        @SuppressWarnings("unchecked")
                        Set<String> newCollaborators = (Set<String>) obj;
                        System.out.println("Client: Received collaborator list update: " + newCollaborators);
                        collaborators.clear();
                        collaborators.addAll(newCollaborators);
                        updateCollaboratorList();
                    } else if (obj instanceof StateUpdate) { // Receiving initial canvas state
                         StateUpdate update = (StateUpdate) obj;
                         System.out.println("Client: Received StateUpdate for tab " + update.tabId);
                         SwingUtilities.invokeLater(() -> processStateUpdate(update));
                    } else {
                        System.out.println("Client: Received unknown object type: " + obj.getClass().getName());
                    }

                } catch (EOFException e) {
                    System.out.println("Client: Server closed connection (EOF).");
                    e.printStackTrace();
                    break;
                } catch (SocketException e) {
                    System.out.println("Client: Socket exception (likely server disconnected): " + e.getMessage());
                    e.printStackTrace();
                    break;
                } catch (IOException | ClassNotFoundException e) {
                    System.err.println("Client: Error reading from server: " + e.getMessage());
                     e.printStackTrace();
                    break; // Assume server disconnected on error
                }
            }
        } catch (ConnectException e) {
            final String errorMsg = "Connection refused. Is the server running at " + host + ":" + port + "?";
             System.err.println(errorMsg);
             SwingUtilities.invokeLater(() -> {
                 JOptionPane.showMessageDialog(this, errorMsg, "Connection Error", JOptionPane.ERROR_MESSAGE);
                 appendChatMessage(new ChatMessage("System", "Connection failed."));
             });
        } catch (UnknownHostException e) {
             final String errorMsg = "Unknown host: " + host;
             System.err.println(errorMsg);
             SwingUtilities.invokeLater(() -> {
                 JOptionPane.showMessageDialog(this, errorMsg, "Connection Error", JOptionPane.ERROR_MESSAGE);
                 appendChatMessage(new ChatMessage("System", "Connection failed."));
             });
        } catch (IOException e) {
            final String errorMsg = "Connection error: " + e.getMessage();
            System.err.println(errorMsg);
            e.printStackTrace();
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, errorMsg, "Connection Error", JOptionPane.ERROR_MESSAGE);
                appendChatMessage(new ChatMessage("System", "Connection failed."));
            });
        } catch (Exception e) { // Catch other potential setup errors
             final String errorMsg = "An unexpected error occurred during connection: " + e.getMessage();
             System.err.println(errorMsg);
             e.printStackTrace();
             SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, errorMsg, "Error", JOptionPane.ERROR_MESSAGE);
                appendChatMessage(new ChatMessage("System", "Connection failed."));
             });
        } finally {
            // Handle disconnection from server
            System.out.println("Client: Disconnected from server.");
            SwingUtilities.invokeLater(() -> {
                appendChatMessage(new ChatMessage("System", "Disconnected from server."));
            
                 if (!isServerMode) setTitle(getTitle() + " (Disconnected)");
            });
            cleanup(); 
        }
    }

     // Processes a received StateUpdate (initial canvas image)
    private void processStateUpdate(StateUpdate update) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(update.imageData);
            BufferedImage newImage = ImageIO.read(bais);
            if (newImage != null) {
                CanvasPanel targetCanvas = canvasMap.get(update.tabId);
                if (targetCanvas == null) {
                    // If tab doesn't exist, create it
                    System.out.println("Client: Creating tab '" + update.tabId + "' based on received state.");
                    addNewTab(update.tabId); // Add the tab first
                    targetCanvas = canvasMap.get(update.tabId); // Get the newly created canvas
                    if (targetCanvas == null) {
                         System.err.println("Client: Failed to create or find canvas for tab ID: " + update.tabId + " after adding.");
                         return;
                    }
                }
                // Set the image on the correct canvas
                targetCanvas.setImage(newImage);
                System.out.println("Client: Applied received state to tab '" + update.tabId + "'.");
            } else {
                System.err.println("Client: Failed to decode received image data for tab " + update.tabId);
            }
        } catch (IOException e) {
             System.err.println("Client: Error processing StateUpdate for tab " + update.tabId + ": " + e.getMessage());
             e.printStackTrace();
        }
    }


    // Processes a received DrawingAction on the correct tab
    private void processDrawingAction(DrawingAction action) {
        CanvasPanel canvas = canvasMap.get(action.tabId);

        if (canvas == null) {
             // If the tab doesn't exist locally, create it
            System.out.println("Action received for non-existent tab '" + action.tabId + "'. Creating tab.");
            addNewTab(action.tabId);
            canvas = canvasMap.get(action.tabId);
            if (canvas == null) { // Check if creation failed somehow
                 System.err.println("ERROR: Could not find or create canvas for tab ID: " + action.tabId);
                 return;
            }
        }

        
        if (canvas.g2 == null) {
            canvas.ensureGraphicsReady();
        }

        if (action.type.equals("draw")) {
            Graphics2D g = canvas.g2; // Use the canvas's graphics context
             if (g == null) {
                System.err.println("ERROR: Graphics context (g2) is null for tab " + action.tabId);
                return;
            }
            if (action.shapeType.equals("Free Draw")) {
                g.setColor(action.color);
                g.setStroke(new BasicStroke(action.brushSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); // Smoother lines
                g.drawLine(action.startX, action.startY, action.endX, action.endY);
            } else {
                 Drawable shape = null;
                 if (action.shapeType.equals("Line")) shape = new LineShape();
                 else if (action.shapeType.equals("Rectangle")) shape = new RectangleShape();

                 if (shape != null) {
                    
                     shape.draw(g, action.startX, action.startY, action.endX, action.endY,
                             action.color, action.brushSize);
                 }
            }
            canvas.repaint(); // Repaint the specific canvas that was drawn on
        } else if (action.type.equals("clear")) {
            canvas.clearLocalOnly(); // Clear only the image buffer
            canvas.backgroundColor = action.color; // Update background color if needed (usually white for clear)
            canvas.g2.setPaint(canvas.backgroundColor);
            canvas.g2.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.repaint();
        } else if (action.type.equals("bgColor")) {
            canvas.backgroundColor = action.color;
            canvas.clearLocalOnly(); // Also clear when bg changes
            canvas.g2.setPaint(canvas.backgroundColor);
            canvas.g2.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.repaint();
        }
         // No pushToUndoStack here, as this is processing a received action
    }

    // Broadcasts an action to all clients *except* the one specified by 'exclude'
    private void broadcastAction(DrawingAction action, ObjectOutputStream exclude) {
        if (!isServerMode) return; // Only server broadcasts

        System.out.println("Server: Broadcasting action (" + action.type + ") for tab " + action.tabId + " from " + action.username);
        synchronized (clientHandlers) { // Synchronize access to the list
             for (ClientHandler handler : clientHandlers) {
                 try {
                     if (handler.out != exclude) { // Don't send back to sender
                         handler.out.writeObject(action);
                         handler.out.flush();
                         // handler.out.reset(); // Consider using reset() if sending many objects or large objects frequently
                     }
                 } catch (IOException e) {
                     System.err.println("Server: Error broadcasting action to " + handler.username + ": " + e.getMessage());
                     // Consider removing the client handler if broadcast fails repeatedly
                 }
             }
        }
    }

    
    void sendDrawingAction(String type, String shapeType, int startX, int startY, int endX, int endY,
                           Color color, int brushSize, String tabId) {
        if (tabId == null) {
            System.err.println("Error: Attempted to send drawing action with null tabId.");
            return; // Don't send if we don't know the tab
        }
        try {
            DrawingAction action = new DrawingAction(type, shapeType, startX, startY, endX, endY,
                    color, brushSize, username, tabId);

            if (isServerMode) {
                // Server processes locally and broadcasts to all clients
                System.out.println("Server: Sending own action ("+type+") for tab " + tabId);
                processDrawingAction(action); // Process on server's own GUI
                broadcastAction(action, null); // Broadcast to all connected clients
            } else if (out != null) {
                 System.out.println("Client: Sending action ("+type+") for tab " + tabId);
                // Client sends to server
                out.writeObject(action);
                out.flush();
                // out.reset();
            }
        } catch (IOException e) {
            final String errorMsg = "Network error sending drawing action: " + e.getMessage();
            System.err.println(errorMsg);
            e.printStackTrace();
            SwingUtilities.invokeLater(()-> JOptionPane.showMessageDialog(this, errorMsg, "Network Error", JOptionPane.ERROR_MESSAGE));
            // Consider attempting reconnect or notifying user of persistent failure
        }
    }

    private void sendChatMessage(String message) {
        try {
            ChatMessage msg = new ChatMessage(username, message);

            // Add to local chat immediately
            appendChatMessage(msg);

            if (isServerMode) {
                // Server broadcasts to all clients
                System.out.println("Server: Broadcasting own chat message.");
                broadcastChatMessage(msg, null);
            } else if (out != null) {
                // Client sends to server
                 System.out.println("Client: Sending chat message.");
                out.writeObject(msg);
                out.flush();
                // out.reset();
            }
        } catch (IOException e) {
            final String errorMsg = "Network error sending chat message: " + e.getMessage();
            System.err.println(errorMsg);
            e.printStackTrace();
             SwingUtilities.invokeLater(()-> JOptionPane.showMessageDialog(this, errorMsg, "Network Error", JOptionPane.ERROR_MESSAGE));
        }
    }

    private void appendChatMessage(ChatMessage msg) {
        // Ensure this runs on the Event Dispatch Thread
        if (SwingUtilities.isEventDispatchThread()) {
            chatArea.append(msg.username + ": " + msg.message + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength()); // Auto-scroll
        } else {
            SwingUtilities.invokeLater(() -> {
                chatArea.append(msg.username + ": " + msg.message + "\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            });
        }
    }

    private void broadcastChatMessage(ChatMessage msg, ObjectOutputStream exclude) {
        if (!isServerMode) return; // Only server broadcasts

         System.out.println("Server: Broadcasting chat message from " + msg.username);
         synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                try {
                    if (handler.out != exclude) { // Don't send back to sender if specified
                        handler.out.writeObject(msg);
                        handler.out.flush();
                        // handler.out.reset();
                    }
                } catch (IOException e) {
                    System.err.println("Server: Error broadcasting chat message to " + handler.username + ": " + e.getMessage());
                }
            }
        }
    }

    private void broadcastCollaborators() {
        if (!isServerMode) return; // Only server broadcasts

        System.out.println("Server: Broadcasting collaborator list: " + collaborators);
        Set<String> collabCopy = new HashSet<>(collaborators); // Send a copy
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                try {
                    handler.out.writeObject(collabCopy); // Send the Set object
                    handler.out.flush();
                    // handler.out.reset();
                } catch (IOException e) {
                    System.err.println("Server: Error broadcasting collaborator list to " + handler.username + ": " + e.getMessage());
                }
            }
        }
    }

    private void updateCollaboratorList() {
        // Ensure this runs on the Event Dispatch Thread
        if (SwingUtilities.isEventDispatchThread()) {
            collaboratorListModel.clear();
            // Sort list alphabetically? Optional.
             List<String> sortedCollabs = new ArrayList<>(collaborators);
             Collections.sort(sortedCollabs);
            for (String collaborator : sortedCollabs) { // Use the synchronized set
                collaboratorListModel.addElement(collaborator);
            }
        } else {
            SwingUtilities.invokeLater(() -> {
                collaboratorListModel.clear();
                List<String> sortedCollabs = new ArrayList<>(collaborators);
                Collections.sort(sortedCollabs);
                for (String collaborator : sortedCollabs) { // Use the synchronized set
                    collaboratorListModel.addElement(collaborator);
                }
            });
        }
    }

    // Cleanup resources on window close or disconnection
    private void cleanup() {
        System.out.println("Cleanup initiated...");
        try {
            // Close client-side socket if connected
            if (!isServerMode && clientSocket != null && !clientSocket.isClosed()) {
                 System.out.println("Client: Closing connection socket.");
                if (out != null) try { out.close(); } catch (IOException e) {/*ignore*/}
                if (in != null) try { in.close(); } catch (IOException e) {/*ignore*/}
                try { clientSocket.close(); } catch (IOException e) {/*ignore*/}
            }

            // Close server-side resources if server
            if (isServerMode) {
                 System.out.println("Server: Shutting down...");
                 // Notify all clients of shutdown
                 broadcastChatMessage(new ChatMessage("Server", "Server is shutting down."), null);

                 // Close all client connections
                 System.out.println("Server: Closing client handler connections...");
                 // Iterate over a copy to avoid ConcurrentModificationException if broadcast causes removal
                 List<ClientHandler> handlersCopy = new ArrayList<>(clientHandlers);
                 for (ClientHandler handler : handlersCopy) {
                     try {
                        System.out.println("Server: Closing socket for " + handler.username);
                        if (handler.out != null) try { handler.out.close(); } catch (IOException e) {/*ignore*/}
                        if (handler.in != null) try { handler.in.close(); } catch (IOException e) {/*ignore*/}
                        if (handler.socket != null && !handler.socket.isClosed()) handler.socket.close();
                     } catch (IOException ex) {
                         System.err.println("Server: Error closing socket for " + handler.username + ": " + ex.getMessage());
                     }
                 }
                 clientHandlers.clear(); // Clear the list

                 // Close server socket
                 if (serverSocket != null && !serverSocket.isClosed()) {
                     System.out.println("Server: Closing server socket.");
                     serverSocket.close();
                 }
                 System.out.println("Server: Shutdown complete.");
            }
        } catch (IOException e) {
            System.err.println("Error during cleanup: " + e.getMessage());
            e.printStackTrace();
        } finally {
             out = null;
             in = null;
             clientSocket = null;
             serverSocket = null;
             System.out.println("Cleanup finished.");
        }
    }


    // Inner class for the drawing canvas
    class CanvasPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        BufferedImage img; // The drawing buffer
        Graphics2D g2;      // Graphics context for drawing on the buffer
        Color currentColor = Color.BLACK;
        Color backgroundColor = Color.WHITE;
        int prevX, prevY; // For free draw
        int brushSize = 4; // Default medium brush size
        String currentShape = "Free Draw";
        int shapeStartX, shapeStartY; // For drawing shapes like lines/rects
        boolean isEraser = false;
        Stack<BufferedImage> undoStack = new Stack<>();
        Stack<BufferedImage> redoStack = new Stack<>();
        Whiteboard parentBoard; // Reference to the main Whiteboard frame
        String tabId; // The ID (title) of this canvas tab


        public CanvasPanel(Whiteboard parentBoard, String tabId) {
            this.parentBoard = parentBoard;
            this.tabId = tabId; // Store the tab ID
            setBackground(Color.WHITE); // Default background
            setDoubleBuffered(false); // We manage our own buffer

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                     if (g2 == null) ensureGraphicsReady(); // Ensure g2 exists
                    shapeStartX = prevX = e.getX();
                    shapeStartY = prevY = e.getY();
                    pushToUndoStack(); // Save state *before* drawing starts
                    redoStack.clear(); // Clear redo stack on new action
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                     if (g2 == null) return; // Should not happen if mousePressed worked
                    if (!currentShape.equals("Free Draw")) {
                        // Draw the final shape onto the buffer
                        drawShape(e.getX(), e.getY());
                        // Send the completed shape action
                        parentBoard.sendDrawingAction("draw", currentShape, shapeStartX, shapeStartY, e.getX(), e.getY(),
                                currentColor, brushSize, tabId); // Use stored tabId
                        repaint(); // Repaint to show the final shape from the buffer
                        // No pushToUndoStack here, already pushed on mousePressed
                    }
                    // For Free Draw, individual segments were already sent and drawn
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                     if (g2 == null) return; // Safety check
                    int x = e.getX();
                    int y = e.getY();

                    if (currentShape.equals("Free Draw")) {
                        Color drawColor = isEraser ? backgroundColor : currentColor;
                        g2.setColor(drawColor);
                        // Use BasicStroke for better control over line ends/joins
                        g2.setStroke(new BasicStroke(brushSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawLine(prevX, prevY, x, y);

                        // Send this segment of the free draw
                        parentBoard.sendDrawingAction("draw", "Free Draw", prevX, prevY, x, y,
                                drawColor, brushSize, tabId); // Use stored tabId

                        prevX = x;
                        prevY = y;
                        repaint(); // Repaint to show the segment just drawn
                    } else {
                        // Optional: Provide visual feedback while dragging for shapes
                        // This would require drawing on the component's Graphics (g)
                        // temporarily, and clearing it on the next drag/release.
                        // Example: repaint() then in paintComponent draw the temporary shape.
                        // For simplicity, we only draw the final shape on mouseRelease.
                        repaint(); // Request repaint to potentially draw temporary shape in paintComponent
                    }
                }
            });
        }

        // Called when the panel needs to be painted or repainted
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g); // Clears the panel (important!)
            ensureGraphicsReady(); // Make sure buffer and g2 exist and match size

            // Draw the buffered image onto the panel
            g.drawImage(img, 0, 0, null);

            // Optional: Draw temporary shape while dragging (if not Free Draw)
            // This requires tracking the current mouse position during drag
            // Point currentMousePos = this.getMousePosition();
            // if (!currentShape.equals("Free Draw") && currentMousePos != null && getBounds().contains(currentMousePos) && MouseInfo.getPointerInfo().getLocation() != null && SwingUtilities.isLeftMouseButton(???) /* Need mouse state */ ) {
            //     Graphics2D gPanel = (Graphics2D) g.create(); // Use component's graphics
            //     gPanel.setColor(currentColor);
            //     gPanel.setStroke(new BasicStroke(brushSize));
            //     if (currentShape.equals("Line")) {
            //         gPanel.drawLine(shapeStartX, shapeStartY, currentMousePos.x, currentMousePos.y);
            //     } else if (currentShape.equals("Rectangle")) {
            //          gPanel.drawRect(Math.min(shapeStartX, currentMousePos.x), Math.min(shapeStartY, currentMousePos.y),
            //              Math.abs(currentMousePos.x - shapeStartX), Math.abs(currentMousePos.y - shapeStartY));
            //     }
            //     gPanel.dispose();
            // }
        }

        // Ensures the BufferedImage and its Graphics2D context are created and match the panel size
        void ensureGraphicsReady() {
             if (img == null || img.getWidth() != getWidth() || img.getHeight() != getHeight()) {
                  BufferedImage oldImage = img;
                  if (getWidth() > 0 && getHeight() > 0) {
                     img = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
                     if (g2 != null) {
                        g2.dispose(); // Dispose old graphics context if it exists
                     }
                     g2 = img.createGraphics();
                     g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                     g2.setPaint(backgroundColor); // Initialize with background color
                     g2.fillRect(0, 0, getWidth(), getHeight());

                     // If there was an old image (e.g., resize), draw it onto the new buffer
                     if (oldImage != null) {
                         g2.drawImage(oldImage, 0, 0, null);
                         oldImage.flush(); // Release resources of the old image
                     }

                     System.out.println("Canvas '" + tabId + "': Graphics ready (" + getWidth() + "x" + getHeight() + ")");
                     // Don't push to undo stack here, only on explicit actions
                  } else {
                      img = null;
                      g2 = null;
                  }
             }
        }

        // Draws the selected shape onto the internal buffer (g2)
        private void drawShape(int endX, int endY) {
             if (g2 == null) ensureGraphicsReady();
             if (g2 == null) return; // Still null? Can't draw.

            Drawable shape = null;
            if (currentShape.equals("Line")) shape = new LineShape();
            else if (currentShape.equals("Rectangle")) shape = new RectangleShape();

            if (shape != null) {
                 System.out.println("Canvas '" + tabId + "': Drawing final shape " + currentShape);
                // Properties are passed directly, color/size are current state
                shape.draw(g2, shapeStartX, shapeStartY, endX, endY, currentColor, brushSize);
            }
        }

        // Public methods for toolbar actions

        public void clear() {
            if (g2 == null) ensureGraphicsReady();
             if (g2 == null) return;
            pushToUndoStack();
            g2.setPaint(backgroundColor);
            g2.fillRect(0, 0, getWidth(), getHeight());
            parentBoard.sendDrawingAction("clear", null, 0, 0, 0, 0, backgroundColor, 0, tabId);
            repaint();
        }

        // Clear only the local image buffer, used when receiving a clear action
        public void clearLocalOnly() {
            if (g2 == null) ensureGraphicsReady();
             if (g2 == null) return;
            // Don't push to undo stack, this is processing a received action
            g2.setPaint(backgroundColor); // Use the current background color
            g2.fillRect(0, 0, getWidth(), getHeight());
            repaint();
        }


        public void pickColor() {
            Color chosen = JColorChooser.showDialog(this, "Pick a Drawing Color", currentColor);
            if (chosen != null) {
                currentColor = chosen;
                isEraser = false; // Picking a color disables eraser
                System.out.println("Canvas '" + tabId + "': Color set to " + currentColor);
            }
        }

        public void pickBackgroundColor() {
            Color chosen = JColorChooser.showDialog(this, "Pick Background Color", backgroundColor);
            if (chosen != null) {
                pushToUndoStack(); // Save state before changing background
                backgroundColor = chosen;
                 System.out.println("Canvas '" + tabId + "': Background color set to " + backgroundColor);
                // Clear canvas with new background color and send action
                g2.setPaint(backgroundColor);
                g2.fillRect(0, 0, getWidth(), getHeight());
                parentBoard.sendDrawingAction("bgColor", null, 0, 0, 0, 0, backgroundColor, 0, tabId);
                repaint();
            }
        }

        public void useEraser() {
            isEraser = true;
        }

        // --- UNDO/REDO (LOCAL ONLY) ---
        public void undo() {
            if (!undoStack.isEmpty()) {
                 System.out.println("Canvas '" + tabId + "': Undo");
                redoStack.push(copyImage(img)); // Save current state to redo stack
                img = undoStack.pop(); // Restore previous state
                 if (g2 != null) g2.dispose(); // Dispose old graphics context
                g2 = img.createGraphics(); // Create new graphics context from restored image
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                repaint();
                 // Note: This action is purely local and not sent over the network.
            } else {
                 System.out.println("Canvas '" + tabId + "': Undo stack empty");
            }
        }

        public void redo() {
            if (!redoStack.isEmpty()) {
                System.out.println("Canvas '" + tabId + "': Redo");
                undoStack.push(copyImage(img)); // Save current state to undo stack
                img = redoStack.pop(); // Restore undone state
                if (g2 != null) g2.dispose();
                g2 = img.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                repaint();
                // Note: This action is purely local and not sent over the network.
            } else {
                System.out.println("Canvas '" + tabId + "': Redo stack empty");
            }
        }

        public void setBrushSize(String size) {
            switch (size) {
                case "Small": brushSize = 2; break;
                case "Medium": brushSize = 4; break;
                case "Large": brushSize = 8; break;
                default: brushSize = 4; break; // Default to medium
            }
            System.out.println("Canvas '" + tabId + "': Brush size set to " + brushSize + " (" + size + ")");
        }

        public void setShape(String shape) {
            currentShape = shape;
            isEraser = false; // Selecting a shape disables eraser
             parentBoard.eraserButton.setSelected(false); // Update toolbar display (if JToggleButton used)
             System.out.println("Canvas '" + tabId + "': Shape set to " + currentShape);
        }

        public void saveImage() {
            if (img == null) {
                 JOptionPane.showMessageDialog(this, "Canvas is empty, nothing to save.", "Save Image", JOptionPane.WARNING_MESSAGE);
                 return;
            }
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Save Canvas Image");
            // Suggest filename based on tab title
            fileChooser.setSelectedFile(new File(tabId.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png"));
            fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG Images (*.png)", "png"));

            int userSelection = fileChooser.showSaveDialog(this.parentBoard); // Show relative to the main frame
            if (userSelection == JFileChooser.APPROVE_OPTION) {
                File fileToSave = fileChooser.getSelectedFile();
                String filePath = fileToSave.getAbsolutePath();
                // Ensure .png extension
                if (!filePath.toLowerCase().endsWith(".png")) {
                    fileToSave = new File(filePath + ".png");
                }

                 // Confirm overwrite
                 if (fileToSave.exists()) {
                     int overwriteConfirm = JOptionPane.showConfirmDialog(this.parentBoard,
                             "File already exists. Overwrite?", "Confirm Overwrite", JOptionPane.YES_NO_OPTION);
                     if (overwriteConfirm == JOptionPane.NO_OPTION) {
                         return; // User cancelled
                     }
                 }


                try {
                    ensureGraphicsReady(); // Make sure g2 reflects the current img state
                    ImageIO.write(img, "png", fileToSave);
                    JOptionPane.showMessageDialog(this.parentBoard, "Image saved successfully as\n" + fileToSave.getName(), "Save Successful", JOptionPane.INFORMATION_MESSAGE);
                     System.out.println("Canvas '" + tabId + "': Image saved to " + fileToSave.getAbsolutePath());
                } catch (IOException e) {
                     System.err.println("Canvas '" + tabId + "': Error saving image: " + e.getMessage());
                     e.printStackTrace();
                    JOptionPane.showMessageDialog(this.parentBoard,
                            "Error saving image: " + e.getMessage(),
                            "Save Error",
                            JOptionPane.ERROR_MESSAGE);
                } catch (Exception e) { // Catch other potential errors (e.g., security exceptions)
                    System.err.println("Canvas '" + tabId + "': Unexpected error saving image: " + e.getMessage());
                    e.printStackTrace();
                     JOptionPane.showMessageDialog(this.parentBoard,
                            "An unexpected error occurred during saving:\n" + e.getMessage(),
                            "Save Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        // Pushes a *copy* of the current image onto the undo stack
        private void pushToUndoStack() {
             if (img == null) return; // Don't push if nothing exists
            undoStack.push(copyImage(img));
            // Optional: Limit undo stack size
            // if (undoStack.size() > MAX_UNDO_LEVELS) { undoStack.remove(0); }
             System.out.println("Canvas '" + tabId + "': Pushed state to undo stack (size: " + undoStack.size() + ")");
        }

        // Creates a deep copy of a BufferedImage
        private BufferedImage copyImage(BufferedImage source) {
            if (source == null) return null;
            BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());
            Graphics2D g2d = copy.createGraphics();
            // Ensure rendering hints are copied if necessary, though usually not needed for simple copy
            // g2d.setRenderingHints(this.g2.getRenderingHints()); // Copy hints if needed
            g2d.drawImage(source, 0, 0, null);
            g2d.dispose(); // Dispose the temporary graphics context used for copying
            return copy;
        }

         // Getter for the current canvas image (for state synchronization)
        public BufferedImage getImage() {
            // Return a copy to prevent external modification? Depends on usage.
            // For sending state, the current 'img' is fine as it will be serialized immediately.
            ensureGraphicsReady(); // Ensure it's up-to-date
            return img;
        }

        // Setter for the canvas image (used when receiving state)
        public void setImage(BufferedImage newImage) {
             if (newImage == null) return;

             System.out.println("Canvas '" + tabId + "': Setting image from received state.");
             // Create a copy to own the image data
             img = copyImage(newImage);

             // Update panel size if necessary to match image (or resize image to fit panel?)
             // For now, assume panel size is correct or will adapt. We just replace the buffer.
             // setPreferredSize(new Dimension(img.getWidth(), img.getHeight())); // Example if panel should resize

             if (g2 != null) {
                 g2.dispose(); // Dispose old graphics context
             }
             // Create new graphics context based on the new image
             g2 = img.createGraphics();
             g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
             backgroundColor = new Color(img.getRGB(0, 0)); // Infer background from top-left pixel? Risky. Assume white or keep current? Let's keep current BG.
             // g2.setPaint(backgroundColor); // No need to fill, image is already set

             // Clear undo/redo stacks as the history is now invalid
             undoStack.clear();
             redoStack.clear();
             pushToUndoStack(); // Add the new state as the base for future undos

             repaint(); // Repaint the panel with the new image
             revalidate(); // Notify layout manager if size potentially changed
        }
    } // End of CanvasPanel class


    // Main method to launch the application
    public static void main(String[] args) {
        // Set Look and Feel (Optional, makes it look nicer)
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            System.err.println("Couldn't set system look and feel.");
        }

        // Use SwingUtilities.invokeLater to ensure GUI creation happens on the EDT
        SwingUtilities.invokeLater(() -> {
            // 1. Choose Mode (Server/Client)
            String[] options = {"Start as Server", "Connect as Client"};
            int choice = JOptionPane.showOptionDialog(null,
                    "Choose whiteboard mode:",
                    "Whiteboard Startup",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, // Use question icon
                    null,
                    options,
                    options[0]);

            if (choice == JOptionPane.CLOSED_OPTION) {
                 System.out.println("Startup cancelled by user.");
                 System.exit(0); // Exit if dialog is closed
            }

            // 2. Get Username
            String username = null;
            while (username == null || username.trim().isEmpty()) {
                username = JOptionPane.showInputDialog("Enter your username:");
                 if (username == null) { // User pressed cancel
                     System.out.println("Username entry cancelled by user.");
                     System.exit(0);
                 }
                 if (username.trim().isEmpty()) {
                      JOptionPane.showMessageDialog(null, "Username cannot be empty.", "Invalid Username", JOptionPane.WARNING_MESSAGE);
                 }
            }
            username = username.trim(); // Use the trimmed username

            // 3. Get Network Details based on mode
            if (choice == 0) { // Server Mode
                String portStr = null;
                int port = 5000; // Default port
                boolean validInput = false;
                while(!validInput) {
                    portStr = JOptionPane.showInputDialog("Enter port number for the server:", String.valueOf(port));
                    if (portStr == null) { // User cancelled
                         System.out.println("Port entry cancelled by user.");
                         System.exit(0);
                    }
                    try {
                        port = Integer.parseInt(portStr);
                        if (port > 0 && port <= 65535) {
                            validInput = true;
                        } else {
                             JOptionPane.showMessageDialog(null, "Port number must be between 1 and 65535.", "Invalid Port", JOptionPane.WARNING_MESSAGE);
                        }
                    } catch (NumberFormatException e) {
                        JOptionPane.showMessageDialog(null, "Invalid port number. Please enter a number.", "Invalid Port", JOptionPane.WARNING_MESSAGE);
                    }
                }
                System.out.println("Starting as SERVER on port " + port + " with username " + username);
                new Whiteboard(true, null, port, username);

            } else { // Client Mode
                String host = "localhost"; // Default host
                String portStr = null;
                int port = 5000; // Default port
                boolean hostValid = false;
                boolean portValid = false;

                while(!hostValid) {
                    host = JOptionPane.showInputDialog("Enter server host address:", host);
                     if (host == null) { // User cancelled
                         System.out.println("Host entry cancelled by user.");
                         System.exit(0);
                     }
                     if (!host.trim().isEmpty()) {
                         hostValid = true;
                         host = host.trim();
                     } else {
                         JOptionPane.showMessageDialog(null, "Host address cannot be empty.", "Invalid Host", JOptionPane.WARNING_MESSAGE);
                     }
                }

                while(!portValid) {
                    portStr = JOptionPane.showInputDialog("Enter server port number:", String.valueOf(port));
                    if (portStr == null) { // User cancelled
                        System.out.println("Port entry cancelled by user.");
                        System.exit(0);
                    }
                    try {
                        port = Integer.parseInt(portStr);
                         if (port > 0 && port <= 65535) {
                            portValid = true;
                        } else {
                             JOptionPane.showMessageDialog(null, "Port number must be between 1 and 65535.", "Invalid Port", JOptionPane.WARNING_MESSAGE);
                        }
                    } catch (NumberFormatException e) {
                         JOptionPane.showMessageDialog(null, "Invalid port number. Please enter a number.", "Invalid Port", JOptionPane.WARNING_MESSAGE);
                    }
                }
                System.out.println("Starting as CLIENT connecting to " + host + ":" + port + " with username " + username);
                new Whiteboard(false, host, port, username);
            }
        });
    }
}
