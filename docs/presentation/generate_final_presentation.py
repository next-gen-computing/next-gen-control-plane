import os
import pptx
from pptx.util import Inches, Pt
from pptx.enum.text import PP_ALIGN
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE

def build_presentation():
    prs = pptx.Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    blank_layout = prs.slide_layouts[6]

    # Color Palette - Modern Dark Cyber-Engineering (from Untitled design)
    BG_COLOR = RGBColor(10, 15, 29)          # #0A0F1D Dark Navy Background
    CARD_BG = RGBColor(17, 26, 46)           # #111A2E Deep Slate Card
    CARD_BG_ALT = RGBColor(22, 34, 59)       # #16223B Elevated Card
    BORDER_CYAN = RGBColor(0, 212, 255)      # #00D4FF Electric Cyan
    BORDER_MUTED = RGBColor(30, 45, 74)      # #1E2D4A Subtle Border
    TEXT_WHITE = RGBColor(255, 255, 255)     # #FFFFFF Pure White
    TEXT_LIGHT = RGBColor(226, 232, 240)     # #E2E8F0 Soft White/Gray
    TEXT_MUTED = RGBColor(148, 163, 184)     # #94A3B8 Slate Gray
    ACCENT_CYAN = RGBColor(0, 212, 255)      # #00D4FF Cyan
    ACCENT_GREEN = RGBColor(0, 255, 136)     # #00FF88 Emerald Green
    ACCENT_AMBER = RGBColor(255, 183, 3)     # #FFB703 Amber / Warning
    ACCENT_PURPLE = RGBColor(139, 92, 246)   # #8B5CF6 Purple
    ACCENT_RED = RGBColor(255, 75, 75)       # #FF4B4B Red

    img_dir = r"C:\Users\LALITH\Documents\next-gen-control-plane\extracted_presentation"
    amc_logo = os.path.join(img_dir, "college_image2.png")
    vtu_logo = os.path.join(img_dir, "college_image3.png")
    arch_diagram = os.path.join(img_dir, "college_image8.png")
    workflow_diagram = os.path.join(img_dir, "college_image9.png")

    def create_slide():
        s = prs.slides.add_slide(blank_layout)
        bg = s.background
        f = bg.fill
        f.solid()
        f.fore_color.rgb = BG_COLOR
        return s

    def add_header(slide, tag_text, title_text, subtitle_text):
        # Top glowing accent line
        line = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.8), Inches(0.35), Inches(0.08), Inches(0.85))
        line.fill.solid()
        line.fill.fore_color.rgb = ACCENT_CYAN
        line.line.color.rgb = ACCENT_CYAN
        
        # Tag & Title box
        tb = slide.shapes.add_textbox(Inches(1.05), Inches(0.28), Inches(11.5), Inches(0.95))
        tf = tb.text_frame
        tf.word_wrap = True
        tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
        
        # Tag paragraph
        p0 = tf.paragraphs[0]
        p0.text = tag_text.upper()
        p0.font.name = "Segoe UI"
        p0.font.size = Pt(9.5)
        p0.font.bold = True
        p0.font.color.rgb = ACCENT_CYAN
        
        # Title paragraph
        p1 = tf.add_paragraph()
        p1.text = title_text
        p1.font.name = "Segoe UI"
        p1.font.size = Pt(20)
        p1.font.bold = True
        p1.font.color.rgb = TEXT_WHITE
        
        # Subtitle paragraph
        p2 = tf.add_paragraph()
        p2.text = subtitle_text
        p2.font.name = "Segoe UI"
        p2.font.size = Pt(11)
        p2.font.color.rgb = TEXT_MUTED

    def add_card(slide, left, top, width, height, bg=CARD_BG, border=BORDER_MUTED, border_width=1.0):
        c = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, height)
        c.fill.solid()
        c.fill.fore_color.rgb = bg
        c.line.color.rgb = border
        c.line.width = Pt(border_width)
        return c

    # =========================================================================
    # SLIDE 1: TITLE SLIDE (Academic Header + Modern High-Tech Card)
    # =========================================================================
    s1 = create_slide()
    
    # Header Logos
    if os.path.exists(amc_logo):
        s1.shapes.add_picture(amc_logo, Inches(0.8), Inches(0.4), height=Inches(1.1))
    if os.path.exists(vtu_logo):
        s1.shapes.add_picture(vtu_logo, Inches(11.4), Inches(0.4), height=Inches(1.1))

    # College Title Text
    c_tb = s1.shapes.add_textbox(Inches(2.0), Inches(0.35), Inches(9.2), Inches(1.2))
    c_tf = c_tb.text_frame
    c_tf.word_wrap = True
    c_tf.margin_left = c_tf.margin_top = c_tf.margin_right = c_tf.margin_bottom = 0
    
    cp0 = c_tf.paragraphs[0]
    cp0.text = "AMC ENGINEERING COLLEGE"
    cp0.font.name = "Segoe UI"
    cp0.font.size = Pt(17)
    cp0.font.bold = True
    cp0.alignment = PP_ALIGN.CENTER
    cp0.font.color.rgb = TEXT_WHITE
    
    cp1 = c_tf.add_paragraph()
    cp1.text = "Affiliated to Visvesvaraya Technological University (VTU), Belagavi"
    cp1.font.name = "Segoe UI"
    cp1.font.size = Pt(11.5)
    cp1.alignment = PP_ALIGN.CENTER
    cp1.font.color.rgb = ACCENT_CYAN
    
    cp2 = c_tf.add_paragraph()
    cp2.text = "Department of Computer Science & Engineering | Major Project Phase – II (BCS786) | 2026–27"
    cp2.font.name = "Segoe UI"
    cp2.font.size = Pt(10)
    cp2.alignment = PP_ALIGN.CENTER
    cp2.font.color.rgb = TEXT_MUTED

    # Main Project Hero Card
    hero_card = add_card(s1, Inches(0.8), Inches(1.8), Inches(11.733), Inches(2.3), bg=CARD_BG_ALT, border=BORDER_CYAN, border_width=1.5)
    h_tb = s1.shapes.add_textbox(Inches(1.2), Inches(2.0), Inches(10.9), Inches(1.9))
    h_tf = h_tb.text_frame
    h_tf.word_wrap = True
    
    hp0 = h_tf.paragraphs[0]
    hp0.text = "PROJECT TITLE:"
    hp0.font.name = "Segoe UI"
    hp0.font.size = Pt(10)
    hp0.font.bold = True
    hp0.font.color.rgb = ACCENT_CYAN
    
    hp1 = h_tf.add_paragraph()
    hp1.text = "NEXT-GEN CONTROL PLANE"
    hp1.font.name = "Segoe UI"
    hp1.font.size = Pt(28)
    hp1.font.bold = True
    hp1.font.color.rgb = TEXT_WHITE
    
    hp2 = h_tf.add_paragraph()
    hp2.text = "Predictive Fault Tolerance & Workload Scheduling for Distributed Edge Clusters"
    hp2.font.name = "Segoe UI"
    hp2.font.size = Pt(14)
    hp2.font.bold = True
    hp2.font.color.rgb = ACCENT_GREEN

    hp3 = h_tf.add_paragraph()
    hp3.text = "Aggregating Heterogeneous Commodity Hardware behind NAT into a Resilient Logical Compute Cluster"
    hp3.font.name = "Segoe UI"
    hp3.font.size = Pt(11)
    hp3.font.color.rgb = TEXT_MUTED

    # Team Members Card (Left)
    add_card(s1, Inches(0.8), Inches(4.35), Inches(5.7), Inches(2.65), bg=CARD_BG, border=BORDER_MUTED)
    t_tb = s1.shapes.add_textbox(Inches(1.1), Inches(4.5), Inches(5.1), Inches(2.3))
    t_tf = t_tb.text_frame
    t_tf.word_wrap = True
    
    tp0 = t_tf.paragraphs[0]
    tp0.text = "PRESENTED BY (BATCH BCS786)"
    tp0.font.name = "Segoe UI"
    tp0.font.size = Pt(11)
    tp0.font.bold = True
    tp0.font.color.rgb = ACCENT_CYAN

    students = [
        ("Lalith Mohan N", "1AM23CS091"),
        ("Keerthika M", "1AM23CS085"),
        ("Nethravathi B M", "1AM23CS118"),
        ("Khushi Shankar", "1AM23CS086")
    ]
    for name, usn in students:
        p = t_tf.add_paragraph()
        p.text = f"•  {name.ljust(22)} {usn}"
        p.font.name = "Consolas"
        p.font.size = Pt(11)
        p.font.color.rgb = TEXT_LIGHT

    # Project Guide Card (Right)
    add_card(s1, Inches(6.833), Inches(4.35), Inches(5.7), Inches(2.65), bg=CARD_BG, border=BORDER_MUTED)
    g_tb = s1.shapes.add_textbox(Inches(7.133), Inches(4.5), Inches(5.1), Inches(2.3))
    g_tf = g_tb.text_frame
    g_tf.word_wrap = True
    
    gp0 = g_tf.paragraphs[0]
    gp0.text = "UNDER THE GUIDANCE OF"
    gp0.font.name = "Segoe UI"
    gp0.font.size = Pt(11)
    gp0.font.bold = True
    gp0.font.color.rgb = ACCENT_GREEN

    gp1 = g_tf.add_paragraph()
    gp1.text = "Prof. Premkumar M K"
    gp1.font.name = "Segoe UI"
    gp1.font.size = Pt(15)
    gp1.font.bold = True
    gp1.font.color.rgb = TEXT_WHITE

    gp2 = g_tf.add_paragraph()
    gp2.text = "Assistant Professor\nDepartment of Computer Science & Engineering\nAMC Engineering College, Bengaluru"
    gp2.font.name = "Segoe UI"
    gp2.font.size = Pt(11)
    gp2.font.color.rgb = TEXT_MUTED

    # =========================================================================
    # SLIDE 2: PROJECT OVERVIEW & MOTIVATION
    # =========================================================================
    s2 = create_slide()
    add_header(s2, "01 // PROJECT OVERVIEW", "Aggregating Heterogeneous Edge Computing Resources",
               "Turning ordinary laptops and desktops into a unified, predictive fault-tolerant cluster")
    
    card_w = Inches(3.64)
    card_h = Inches(5.6)
    card_top = Inches(1.5)
    
    # Card 1: Resource Aggregation
    add_card(s2, Inches(0.8), card_top, card_w, card_h, bg=CARD_BG, border=BORDER_MUTED)
    c1_tb = s2.shapes.add_textbox(Inches(1.05), Inches(1.75), Inches(3.14), Inches(5.1))
    c1_tf = c1_tb.text_frame
    c1_tf.word_wrap = True
    
    p = c1_tf.paragraphs[0]
    p.text = "01 / RESOURCE HARNESSING"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN
    p = c1_tf.add_paragraph()
    p.text = "Commodity Edge Aggregation"
    p.font.name = "Segoe UI"; p.font.size = Pt(16); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = c1_tf.add_paragraph()
    p.text = "\n• Pools available computing power from consumer devices (laptops, PCs) into a single logical cluster.\n\n• Zero Dependency on Expensive Cloud Infrastructure: Replaces costly AWS/GCP instances by harvesting idle edge CPU & memory.\n\n• Residential NAT Traversal: Seamlessly aggregates machines across Wi-Fi networks without requiring public IPs or router port forwarding."
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.color.rgb = TEXT_LIGHT

    # Card 2: Predictive Fault Tolerance
    add_card(s2, Inches(4.84), card_top, card_w, card_h, bg=CARD_BG_ALT, border=BORDER_CYAN, border_width=1.5)
    c2_tb = s2.shapes.add_textbox(Inches(5.09), Inches(1.75), Inches(3.14), Inches(5.1))
    c2_tf = c2_tb.text_frame
    c2_tf.word_wrap = True
    
    p = c2_tf.paragraphs[0]
    p.text = "02 / PREDICTIVE MIGRATION"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    p = c2_tf.add_paragraph()
    p.text = "Proactive Fault Tolerance"
    p.font.name = "Segoe UI"; p.font.size = Pt(16); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = c2_tf.add_paragraph()
    p.text = "\n• Paradigm Shift: Moves work off degrading machines before failure occurs, rather than reacting after a crash.\n\n• Continuous Telemetry Evaluation: Inspects rolling CPU, memory gradients, battery levels, and RTT latency sweeps every 5s.\n\n• Real-Time Mitigation: Achieves ~14 ms detection-to-mitigation latency, eliminating task loss and preventing split-brain states."
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.color.rgb = TEXT_LIGHT

    # Card 3: Dual Workload Orchestration
    add_card(s2, Inches(8.88), card_top, card_w, card_h, bg=CARD_BG, border=BORDER_MUTED)
    c3_tb = s2.shapes.add_textbox(Inches(9.13), Inches(1.75), Inches(3.14), Inches(5.1))
    c3_tf = c3_tb.text_frame
    c3_tf.word_wrap = True
    
    p = c3_tf.paragraphs[0]
    p.text = "03 / WORKLOAD DIVERSITY"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_PURPLE
    p = c3_tf.add_paragraph()
    p.text = "Compute & Containers"
    p.font.name = "Segoe UI"; p.font.size = Pt(16); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = c3_tf.add_paragraph()
    p.text = "\n• Dual Workload Engine: Coordinates both data-parallel math jobs (Prime Sieve) and multi-container Docker Compose stacks.\n\n• Unified Control Interfaces: Full control via rich JavaFX Desktop GUI, embedded Tomcat 3D Web UI, and developer CLI (nx).\n\n• Capability-Aware Scheduling: Dispatches container services exclusively to Docker-capable nodes with port-relay tunneling."
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 3: PROBLEM STATEMENT & OPERATIONAL CHALLENGES
    # =========================================================================
    s3 = create_slide()
    add_header(s3, "02 // PROBLEM STATEMENT", "Operational Challenges in Edge Distributed Clusters",
               "Analyzing the fatal failure modes and limitations of existing cluster orchestrators")
    
    grid_w = Inches(5.7)
    grid_h = Inches(2.65)
    
    # Ch 1: Hardware Degradation
    add_card(s3, Inches(0.8), Inches(1.5), grid_w, grid_h)
    tb = s3.shapes.add_textbox(Inches(1.05), Inches(1.65), Inches(5.2), Inches(2.3))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "CHALLENGE 01 // HARDWARE DEGRADATION"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_AMBER
    p = tf.add_paragraph()
    p.text = "Gradual & Unannounced Failure Trajectories"
    p.font.name = "Segoe UI"; p.font.size = Pt(14); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "• Edge hardware rarely dies instantaneously; failures are preceded by distinct degradation signals: thermal throttling, progressive RAM leaks, Wi-Fi latency escalation, or battery exhaustion on unplugged laptops.\n• Legacy orchestrators treat all alive nodes identically until they completely crash."
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.color.rgb = TEXT_LIGHT

    # Ch 2: Residential NAT
    add_card(s3, Inches(6.833), Inches(1.5), grid_w, grid_h)
    tb = s3.shapes.add_textbox(Inches(7.083), Inches(1.65), Inches(5.2), Inches(2.3))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "CHALLENGE 02 // NETWORK TOPOLOGY"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN
    p = tf.add_paragraph()
    p.text = "Strict Private NAT & Residential Firewalls"
    p.font.name = "Segoe UI"; p.font.size = Pt(14); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "• Distributed nodes connected over home routers sit behind private Carrier-Grade NAT (CGNAT) and stateful firewalls that block all incoming connection attempts.\n• Systems requiring direct inbound listening ports (Kubernetes kubelet) cannot function on consumer laptops without complex VPNs."
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.color.rgb = TEXT_LIGHT

    # Ch 3: Unacceptable Reaction Delays
    add_card(s3, Inches(0.8), Inches(4.35), grid_w, grid_h)
    tb = s3.shapes.add_textbox(Inches(1.05), Inches(4.5), Inches(5.2), Inches(2.3))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "CHALLENGE 03 // LATENCY PENALTY"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_RED
    p = tf.add_paragraph()
    p.text = "Protracted Timeout Delays in Legacy Schedulers"
    p.font.name = "Segoe UI"; p.font.size = Pt(14); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "• Kubernetes marks an unresponsive node NotReady after ~40s and evicts pods only after ~300s further (total default delay: ~340 seconds / ~5.6 minutes).\n• BOINC volunteer computing reassigns work only when the deadline expires (default: ~10 days). In-flight work and intermediate computations are lost."
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.color.rgb = TEXT_LIGHT

    # Ch 4: Heterogeneous Imbalance
    add_card(s3, Inches(6.833), Inches(4.35), grid_w, grid_h)
    tb = s3.shapes.add_textbox(Inches(7.083), Inches(4.5), Inches(5.2), Inches(2.3))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "CHALLENGE 04 // SCHEDULING IMBALANCE"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_PURPLE
    p = tf.add_paragraph()
    p.text = "Uneven Resource Utilization & Stragglers"
    p.font.name = "Segoe UI"; p.font.size = Pt(14); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "• Typical round-robin or unweighted schedulers distribute identical job slices without respecting hardware disparity (e.g. 4-core laptop vs. 16-core workstation).\n• Weaker machines choke on excessive work while powerful machines sit idle, multiplying total job completion time."
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 4: OBJECTIVES & TECHNICAL APPROACHES
    # =========================================================================
    s4 = create_slide()
    add_header(s4, "03 // OBJECTIVES & APPROACH", "The Five Core Engineering Objectives",
               "Technical pillars, implementation approaches, and verified engineering deliverables")
    
    top_pos = Inches(1.5)
    pillars = [
        ("01. CONNECTIVITY", "NAT-Friendly mTLS Transport", "Outbound-only persistent gRPC streams with mutual TLS authentication and single-use enrollment tokens.", "Enables nodes behind arbitrary home NATs to join cluster without router port-forwarding.", ACCENT_CYAN),
        ("02. PREDICTION", "Dual-Layer Risk Scoring", "Dual-layer telemetry evaluation combining 3-rule deterministic heuristics with a trained XGBoost ML model on 14-D vectors.", "Identifies pre-failure degradation trends and triggers fenced proactive migration in ~14 ms.", ACCENT_GREEN),
        ("03. SCHEDULING", "Capacity-Weighted Allocation", "Capacity-scoring algorithm C(n) factoring CPU cores, RAM, headroom multipliers, and dynamic failure risk factors.", "Guarantees proportional workload distribution (e.g., exact 6:12 task ratio across heterogeneous nodes).", ACCENT_AMBER),
        ("04. RESILIENCE", "Raft-Replicated Control Plane", "Built-in 3-replica Raft consensus state machine with persistent Write-Ahead Logs (WAL) and leader heartbeats.", "Guarantees cluster state continuity, zero split-brain, and instant election during control plane crash.", ACCENT_PURPLE),
        ("05. EXECUTION", "Multi-Workload Orchestration", "Distributed Docker Compose execution engine with remote build context streaming and cross-node relay networking.", "Centralized management of compute jobs and containerized microservices via Desktop, Web, and CLI.", ACCENT_CYAN)
    ]
    
    for i, (tag, title, tech, outcome, col) in enumerate(pillars):
        y = top_pos + Inches(i * 1.12)
        add_card(s4, Inches(0.8), y, Inches(11.733), Inches(1.0), bg=CARD_BG, border=BORDER_MUTED)
        cbar = s4.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.8), y, Inches(0.08), Inches(1.0))
        cbar.fill.solid(); cbar.fill.fore_color.rgb = col; cbar.line.fill.background()
        
        tb = s4.shapes.add_textbox(Inches(1.05), y + Inches(0.08), Inches(11.3), Inches(0.84))
        tf = tb.text_frame; tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = f"{tag}: {title}"
        p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = col
        p = tf.add_paragraph()
        p.text = f"Approach: {tech}   ➔   Outcome: {outcome}"
        p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 5: SCOPE BOUNDARIES & INGRESS TOPOLOGY
    # =========================================================================
    s5 = create_slide()
    add_header(s5, "04 // SCOPE & TOPOLOGY", "Scope Boundaries & Ingress Network Invariant",
               "Architectural guarantees, operational responsibilities, and strict edge boundary constraints")
    
    # Left Card: In-Scope Capabilities
    add_card(s5, Inches(0.8), Inches(1.5), Inches(5.7), Inches(4.1), bg=CARD_BG, border=BORDER_CYAN, border_width=1.2)
    tb = s5.shapes.add_textbox(Inches(1.05), Inches(1.65), Inches(5.2), Inches(3.8))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "IN-SCOPE ARCHITECTURAL CAPABILITIES"
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN
    items_in = [
        ("Outbound mTLS Streaming", "Edge nodes traverse residential CGNAT without open listening ports."),
        ("Proactive Failure Mitigation", "Sub-second task re-dispatch before node crash (empirically ~14 ms)."),
        ("Capacity-Weighted Slicing", "Mathematical C(n) workload division honoring cores and RAM."),
        ("Atomic Cryptographic Fencing", "Monotonic fencing tokens reject stale results from zombie nodes."),
        ("Embedded Raft Consensus", "Custom 3-replica zero-external-dependency consensus with WAL."),
        ("Dual-Workload Support", "Coordinates batch compute jobs and Docker Compose container stacks.")
    ]
    for h, b in items_in:
        p = tf.add_paragraph()
        p.text = f"✔  {h}: {b}"
        p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # Right Card: Out-of-Scope Boundaries
    add_card(s5, Inches(6.833), Inches(1.5), Inches(5.7), Inches(4.1), bg=CARD_BG, border=BORDER_MUTED)
    tb = s5.shapes.add_textbox(Inches(7.083), Inches(1.65), Inches(5.2), Inches(3.8))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "OUT-OF-SCOPE BOUNDARIES & SYSTEM ASSUMPTIONS"
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = ACCENT_AMBER
    items_out = [
        ("Inbound Listening Ports", "Nodes never listen on external ports; all inward connections forbidden."),
        ("Live Memory Migration", "Active RAM state migration of stateful processes is omitted (stateless)."),
        ("Physical Hardware Repair", "Physical defects (blown fan, disk sector failure) cordoned, not fixed."),
        ("Byzantine Adversaries", "Crash-Fault-Tolerant (CFT) model assumes authenticated non-malicious nodes."),
        ("Uncontrolled Over-Subscription", "Tasks are queued when aggregate capacity is saturated."),
        ("Direct Inter-Node Peering", "All traffic mediated via Control Plane relay stream for security.")
    ]
    for h, b in items_out:
        p = tf.add_paragraph()
        p.text = f"✖  {h}: {b}"
        p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_MUTED

    # Bottom Banner: The NAT Ingress Invariant
    add_card(s5, Inches(0.8), Inches(5.75), Inches(11.733), Inches(1.35), bg=CARD_BG_ALT, border=BORDER_MUTED)
    tb = s5.shapes.add_textbox(Inches(1.05), Inches(5.85), Inches(11.3), Inches(1.15))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "THE RESIDENTIAL NAT INGRESS INVARIANT"
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    p = tf.add_paragraph()
    p.text = "In edge networks, consumer routers maintain stateful firewall tables that reject incoming connection attempts from the public internet. The Next-Gen Control Plane enforces a strict unidirectional connection establishment invariant: the edge node agent always initiates an outbound TLS connection to Control Plane gRPC port 50051. The established persistent HTTP/2 stream allows bi-directional multiplexing without requiring static IPs or port-forwarding."
    p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 6: LITERATURE SURVEY & RESEARCH BASELINES
    # =========================================================================
    s6 = create_slide()
    add_header(s6, "05 // LITERATURE REVIEW", "Academic Literature Survey & Theoretical Baselines",
               "Synthesizing seminal distributed systems research into a cohesive edge control plane")
    
    lit_papers = [
        ("Ongaro & Ousterhout (2014)", "Raft Consensus Algorithm", "Understandable replicated state machine vs Paxos.", "Adopted for embedded 3-replica state coordination and WAL in Java.", ACCENT_CYAN),
        ("Dean & Ghemawat (Google, 2004)", "MapReduce Programming Model", "Simplified data processing on large commodity clusters.", "Formed the basis for dynamic range splitting and barrier aggregation.", ACCENT_GREEN),
        ("Verma et al. (Google, 2015)", "Borg Cluster Management", "Production cluster orchestration and resource packing.", "Inspired capacity-weighted scheduling and declarative state management.", ACCENT_AMBER),
        ("Anderson (UC Berkeley, 2004)", "BOINC Volunteer Computing", "Public-resource computing over consumer internet.", "Adapted outbound-only client registration while replacing 10-day timeouts.", ACCENT_PURPLE),
        ("McMahan et al. (2017)", "Federated Edge Telemetry", "Decentralized metrics collection without cloud overhead.", "Lightweight edge node agents scrape and stream 14-D hardware vectors.", ACCENT_CYAN),
        ("Chen & Guestrin (2016)", "XGBoost Machine Learning", "Scalable tree boosting for structured tabular data.", "Trained on 300,000 samples for sub-millisecond edge failure classification.", ACCENT_GREEN)
    ]
    
    for i, (authors, title, summary, adoption, col) in enumerate(lit_papers):
        col_idx = i % 2
        row_idx = i // 2
        x = Inches(0.8) if col_idx == 0 else Inches(6.833)
        y = Inches(1.5) + Inches(row_idx * 1.85)
        
        add_card(s6, x, y, Inches(5.7), Inches(1.72), bg=CARD_BG, border=BORDER_MUTED)
        cbar = s6.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, y, Inches(0.06), Inches(1.72))
        cbar.fill.solid(); cbar.fill.fore_color.rgb = col; cbar.line.fill.background()
        
        tb = s6.shapes.add_textbox(x + Inches(0.2), y + Inches(0.08), Inches(5.35), Inches(1.55))
        tf = tb.text_frame; tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = f"{authors} // {title}"
        p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True; p.font.color.rgb = col
        p = tf.add_paragraph()
        p.text = f"Core Finding: {summary}"
        p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT
        p = tf.add_paragraph()
        p.text = f"Project Adoption: {adoption}"
        p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_MUTED

    # =========================================================================
    # SLIDE 7: EXISTING SYSTEM VS PROPOSED NEXT-GEN SYSTEM
    # =========================================================================
    s7 = create_slide()
    add_header(s7, "06 // COMPARATIVE ANALYSIS", "Existing Cluster Orchestrators vs. Next-Gen Control Plane",
               "Direct architectural comparison against Kubernetes, BOINC, and conventional distributed schedulers")
    
    # Table Comparison
    table_shape = s7.shapes.add_table(7, 4, Inches(0.8), Inches(1.5), Inches(11.733), Inches(5.5))
    table = table_shape.table
    table.columns[0].width = Inches(2.2)
    table.columns[1].width = Inches(3.0)
    table.columns[2].width = Inches(3.0)
    table.columns[3].width = Inches(3.533)
    
    headers = ["Evaluation Metric", "Kubernetes (K8s)", "BOINC Volunteer Grid", "Next-Gen Control Plane"]
    for j, h in enumerate(headers):
        cell = table.cell(0, j)
        cell.fill.solid()
        cell.fill.fore_color.rgb = CARD_BG_ALT
        p = cell.text_frame.paragraphs[0]
        p.text = h
        p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True
        p.font.color.rgb = ACCENT_CYAN if j == 3 else TEXT_WHITE

    row_data = [
        ("Fault Mitigation", "Reactive: marks NotReady after 40s; evicts after 300s (total ~340s).", "Reactive: reschedules only upon task deadline expiry (~10 days default).", "Proactive: predicts degradation via ML and mitigates in ~14 ms."),
        ("Edge NAT Traversal", "Inbound Kubelet port required; requires overlay mesh / VPN on WAN.", "Outbound HTTP polling; high round-trip latency and heavy polling overhead.", "Outbound persistent mTLS gRPC bi-directional stream; zero open ports."),
        ("Workload Slicing", "Static CPU/RAM resource limits; prone to stragglers on heterogeneous PCs.", "Coarse unweighted task pull; client decides when to request slices.", "Capacity-weighted C(n) slicing factoring cores, RAM, and dynamic risk."),
        ("Cluster Consensus", "External etcd cluster dependency; heavyweight quorum overhead.", "Single MySQL central database; single-point-of-failure bottleneck.", "Embedded 3-replica Java Raft state machine with persistent WAL."),
        ("Task Fencing", "Potential split-brain during partial network partitions.", "Redundant multi-worker execution (wastes 200-300% compute cycles).", "Cryptographic monotonic fencing tokens reject late results atomically."),
        ("Runtime Footprint", "Heavyweight: 1-2 GB RAM per worker node daemon.", "Medium: background daemon without real-time telemetry streaming.", "Ultra-lightweight: <60 MB JVM heap, native CLI & Three.js 3D Web UI.")
    ]
    for i, row in enumerate(row_data):
        for j, val in enumerate(row):
            cell = table.cell(i + 1, j)
            cell.fill.solid()
            cell.fill.fore_color.rgb = RGBColor(14, 22, 38) if i % 2 == 0 else CARD_BG
            p = cell.text_frame.paragraphs[0]
            p.text = val
            p.font.name = "Segoe UI"; p.font.size = Pt(9.5)
            if j == 0:
                p.font.bold = True; p.font.color.rgb = TEXT_WHITE
            elif j == 3:
                p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
            else:
                p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 8: SYSTEM REQUIREMENTS & TECHNICAL SPECIFICATIONS
    # =========================================================================
    s8 = create_slide()
    add_header(s8, "07 // SYSTEM SPECIFICATIONS", "Hardware, Software & Network Infrastructure Specifications",
               "Detailed minimum and recommended operational baselines across cluster components")
    
    specs = [
        ("CENTRAL CONTROL PLANE SERVER", [
            "Processor: Quad-Core x86_64 or ARM64 (2.4 GHz+)",
            "System Memory: 8 GB RAM (16 GB Recommended for large clusters)",
            "Storage: 20 GB SSD for Raft Write-Ahead Logs & State Snapshots",
            "Operating System: Linux (Ubuntu 22.04+), Windows 10/11, macOS",
            "Software Stack: Java 21 LTS, Python 3.11 (XGBoost Daemon), Tomcat 10.1",
            "Ingress Ports: 50051 (gRPC Worker Ingress), 8080/8085 (Web UI & REST API)"
        ], ACCENT_CYAN),
        ("DISTRIBUTED EDGE WORKER NODES", [
            "Processor: Dual-Core CPU (Intel/AMD/Apple Silicon)",
            "System Memory: 4 GB RAM minimum (8 GB+ recommended)",
            "Disk Space: 5 GB available for container cache & scratch buffers",
            "Operating System: Cross-platform (Windows, Linux, macOS)",
            "Software Stack: JRE 21+ or native binary; Docker Engine 24+ (Compose tasks)",
            "Network Ingress: ZERO open listening ports required (outbound only)"
        ], ACCENT_GREEN),
        ("SOFTWARE DEPENDENCIES & LIBRARIES", [
            "Network Protocol: gRPC 1.62.2, Protocol Buffers 3.25.3, HTTP/2",
            "Consensus: Custom Raft Java implementation with disk-backed WAL",
            "Machine Learning: Python 3.11, XGBoost 2.0.3, scikit-learn, joblib",
            "Web UI / Frontend: Three.js 3D WebGL, Vanilla JS, CSS3 Cyberpunk Grid",
            "Desktop UI: JavaFX 21 SDK with dark high-contrast dashboard theme",
            "CLI Management: 'nx' unified developer command-line tool"
        ], ACCENT_AMBER),
        ("NETWORK TOPOLOGY & CRYPTOGRAPHY", [
            "Transport Protocol: TLS 1.3 with mutual certificate verification (mTLS)",
            "Heartbeat Cadence: 5.0s telemetry sweep window with 2.5s jitter buffer",
            "Raft Consensus Timers: Heartbeat = 50ms, Election = 150ms-300ms",
            "NAT Traversal: Outbound persistent TCP streaming over port 50051",
            "Security Tokens: Single-use enrollment tokens, SHA-256 HMAC signatures",
            "Isolation: Monotonic fencing tokens guaranteeing zero split-brain writes"
        ], ACCENT_PURPLE)
    ]
    for i, (title, items, col) in enumerate(specs):
        col_idx = i % 2
        row_idx = i // 2
        x = Inches(0.8) if col_idx == 0 else Inches(6.833)
        y = Inches(1.5) + Inches(row_idx * 2.85)
        
        add_card(s8, x, y, Inches(5.7), Inches(2.7), bg=CARD_BG, border=BORDER_MUTED)
        cbar = s8.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, y, Inches(0.08), Inches(2.7))
        cbar.fill.solid(); cbar.fill.fore_color.rgb = col; cbar.line.fill.background()
        
        tb = s8.shapes.add_textbox(x + Inches(0.2), y + Inches(0.1), Inches(5.35), Inches(2.5))
        tf = tb.text_frame; tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = title
        p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = col
        for it in items:
            p = tf.add_paragraph()
            p.text = f"•  {it}"
            p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 9: MODULAR SYSTEM ARCHITECTURE: FIVE-LAYER CORE
    # =========================================================================
    s9 = create_slide()
    add_header(s9, "08 // ARCHITECTURAL DECOMPOSITION", "Five-Layer Modular Architecture Decomposition",
               "Decoupled layering ensuring fault isolation, horizontal scalability, and clean abstraction")
    
    layers = [
        ("LAYER 1: EDGE INGRESS & TRANSPORT", "Persistent HTTP/2 bidi gRPC streams, single-use token enrollment, mutual TLS (mTLS) handshake verification, and real-time connection state registry.", ACCENT_CYAN),
        ("LAYER 2: CONSENSUS & CLUSTER STATE", "Custom 3-replica Java Raft state machine, Write-Ahead Log (WAL) with JSON persistence, leader heartbeats, and atomic term-based cluster state transition.", ACCENT_GREEN),
        ("LAYER 3: PREDICTIVE FAULT TOLERANCE", "Rolling telemetry window buffer, 3-rule deterministic degradation heuristics, IPC-coupled XGBoost ML inference engine, and proactive migration coordinator.", ACCENT_AMBER),
        ("LAYER 4: SCHEDULING & WORKLOAD DISPATCH", "Dynamic capacity calculator C(n), prime sieve search space partitioner, Docker Compose runner with port-relay tunneling, and monotonic cryptographic fencing.", ACCENT_PURPLE),
        ("LAYER 5: UNIFIED INTERFACES & CONTROL", "Interactive Three.js 3D orbital cluster visualizer, embedded Tomcat 10 REST API server, high-performance JavaFX desktop dashboard, and 'nx' engineering CLI.", ACCENT_CYAN)
    ]
    for i, (title, desc, col) in enumerate(layers):
        y = Inches(1.5) + Inches(i * 1.12)
        add_card(s9, Inches(0.8), y, Inches(11.733), Inches(1.0), bg=CARD_BG, border=BORDER_MUTED)
        cbar = s9.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.8), y, Inches(0.08), Inches(1.0))
        cbar.fill.solid(); cbar.fill.fore_color.rgb = col; cbar.line.fill.background()
        
        tb = s9.shapes.add_textbox(Inches(1.05), y + Inches(0.08), Inches(11.3), Inches(0.84))
        tf = tb.text_frame; tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = title
        p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = col
        p = tf.add_paragraph()
        p.text = desc
        p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 10: SYSTEM ARCHITECTURE BLUEPRINT (Diagram Embedded)
    # =========================================================================
    s10 = create_slide()
    add_header(s10, "09 // ARCHITECTURE BLUEPRINT", "End-to-End System Architecture Blueprint",
               "Comprehensive structural view: Ingress Gateway, Raft Consensus, ML Predictor, and Edge Workers")
    
    # Left: Diagram Image Box with Glowing Cyan Frame
    add_card(s10, Inches(0.8), Inches(1.5), Inches(8.0), Inches(5.6), bg=CARD_BG_ALT, border=BORDER_CYAN, border_width=1.5)
    if os.path.exists(arch_diagram):
        s10.shapes.add_picture(arch_diagram, Inches(0.9), Inches(1.6), width=Inches(7.8), height=Inches(5.4))
    
    # Right: Architectural Callouts
    add_card(s10, Inches(9.0), Inches(1.5), Inches(3.533), Inches(5.6), bg=CARD_BG, border=BORDER_MUTED)
    tb = s10.shapes.add_textbox(Inches(9.2), Inches(1.65), Inches(3.133), Inches(5.3))
    tf = tb.text_frame; tf.word_wrap = True
    
    p = tf.paragraphs[0]
    p.text = "KEY ARCHITECTURAL ELEMENTS"
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN
    
    callouts = [
        ("NAT Ingress Gateway", "Accepts persistent outbound gRPC connections from edge nodes without router port configuration.", ACCENT_CYAN),
        ("3-Replica Raft Core", "Synchronous Write-Ahead Logging guarantees state persistence across leader crashes with zero split-brain.", ACCENT_GREEN),
        ("XGBoost Inference Engine", "Sub-millisecond inference on 14-D telemetry vectors evaluated every 5-second heartbeat window.", ACCENT_AMBER),
        ("Heterogeneous Node Pool", "Worker nodes run across Windows, Linux, and macOS, executing data-parallel math or Docker stacks.", ACCENT_PURPLE)
    ]
    for h, b, c in callouts:
        p = tf.add_paragraph()
        p.text = f"\n• {h}:"
        p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = c
        p = tf.add_paragraph()
        p.text = b
        p.font.name = "Segoe UI"; p.font.size = Pt(9); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 11: END-TO-END WORKFLOW PIPELINE (Diagram Embedded)
    # =========================================================================
    s11 = create_slide()
    add_header(s11, "10 // WORKFLOW BLUEPRINT", "End-to-End Operational Lifecycle & Workflow Pipeline",
               "Step-by-step transaction flow from client submission to fenced proactive mitigation")
    
    # Left: Diagram Image Box with Glowing Green Frame
    add_card(s11, Inches(0.8), Inches(1.5), Inches(8.0), Inches(5.6), bg=CARD_BG_ALT, border=BORDER_CYAN, border_width=1.5)
    if os.path.exists(workflow_diagram):
        s11.shapes.add_picture(workflow_diagram, Inches(0.9), Inches(1.6), width=Inches(7.8), height=Inches(5.4))
    
    # Right: Workflow Stages Breakdown
    add_card(s11, Inches(9.0), Inches(1.5), Inches(3.533), Inches(5.6), bg=CARD_BG, border=BORDER_MUTED)
    tb = s11.shapes.add_textbox(Inches(9.2), Inches(1.65), Inches(3.133), Inches(5.3))
    tf = tb.text_frame; tf.word_wrap = True
    
    p = tf.paragraphs[0]
    p.text = "EXECUTION STAGES"
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    
    stages = [
        ("1. Enrollment & Handshake", "Worker presents token; TLS session established."),
        ("2. Real-Time Telemetry", "5s telemetry sweeps stream hardware metrics."),
        ("3. Job Ingestion", "Client submits job via Web UI, Desktop GUI, or CLI."),
        ("4. Capacity Division C(n)", "Scheduler partitions search space proportionally."),
        ("5. Fenced Task Dispatch", "Tasks sent with monotonic tokens over gRPC stream."),
        ("6. Pre-Failure Alert", "XGBoost flags node degradation (R(n) >= 0.70)."),
        ("7. Proactive Re-Routing", "In-flight work migrated to healthy node in ~14ms."),
        ("8. Verification & Reduce", "Barrier verifies fencing token and merges results.")
    ]
    for s_title, s_desc in stages:
        p = tf.add_paragraph()
        p.text = f"{s_title}: {s_desc}"
        p.font.name = "Segoe UI"; p.font.size = Pt(8.8); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 12: COMMUNICATION & SECURITY: OUTBOUND MTLS GRPC
    # =========================================================================
    s12 = create_slide()
    add_header(s12, "11 // SECURE TRANSPORT", "NAT-Tolerant Outbound mTLS gRPC Streaming Architecture",
               "Eliminating residential firewall barriers through persistent bi-directional HTTP/2 transport")
    
    card_w3 = Inches(3.64)
    card_h3 = Inches(5.6)
    
    # Card 1: Outbound Transport Invariant
    add_card(s12, Inches(0.8), Inches(1.5), card_w3, card_h3, bg=CARD_BG, border=BORDER_MUTED)
    tb = s12.shapes.add_textbox(Inches(1.05), Inches(1.75), Inches(3.14), Inches(5.1))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "01 / NAT INGRESS INVARIANT"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN
    p = tf.add_paragraph()
    p.text = "Outbound-Only Ingress"
    p.font.name = "Segoe UI"; p.font.size = Pt(16); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "\n• Residential Router Firewalls: Statefully block all unsolicited incoming TCP packets from the public internet.\n\n• The Invariant: Worker nodes initiate an outbound TLS handshake to port 50051.\n\n• Reverse Channel Dispatch: The established TCP stream allows the Control Plane to push tasks downstream without inbound listening ports on the worker."
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.color.rgb = TEXT_LIGHT

    # Card 2: Mutual TLS & Cryptographic Tokens
    add_card(s12, Inches(4.84), Inches(1.5), card_w3, card_h3, bg=CARD_BG_ALT, border=BORDER_CYAN, border_width=1.5)
    tb = s12.shapes.add_textbox(Inches(5.09), Inches(1.75), Inches(3.14), Inches(5.1))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "02 / IDENTITY & SECURITY"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    p = tf.add_paragraph()
    p.text = "mTLS & Enrollment Tokens"
    p.font.name = "Segoe UI"; p.font.size = Pt(16); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "\n• Dual-Sided Authentication: Both Control Plane and Worker verify cryptographic certificates signed by cluster CA.\n\n• Single-Use Enrollment Tokens: New workers enroll using short-lived cryptographically signed tokens (nx enroll --token).\n\n• Zero Trust Network Architecture: All wire payloads encrypted with TLS 1.3; unauthorized nodes rejected at transport layer."
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.color.rgb = TEXT_LIGHT

    # Card 3: Multiplexed Protobuf RPCs
    add_card(s12, Inches(8.88), Inches(1.5), card_w3, card_h3, bg=CARD_BG, border=BORDER_MUTED)
    tb = s12.shapes.add_textbox(Inches(9.13), Inches(1.75), Inches(3.14), Inches(5.1))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "03 / PROTOCOL BUFFERS"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_PURPLE
    p = tf.add_paragraph()
    p.text = "Multiplexed Streaming"
    p.font.name = "Segoe UI"; p.font.size = Pt(16); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "\n• StreamHeartbeat: Continuous 5s telemetry push carrying 14 hardware features and heartbeat ACK.\n\n• DispatchTaskStream: Bi-directional stream pushing tasks downstream and receiving status ACK.\n\n• StreamTaskResults: High-throughput Protobuf stream delivering computed batches back to barrier aggregator."
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 13: CORE HEURISTIC ALGORITHMS & HEALTH SCORING
    # =========================================================================
    s13 = create_slide()
    add_header(s13, "12 // HEURISTIC ENGINE", "Deterministic Health Scoring & Anomaly Detection Algorithm",
               "Multi-signal degradation heuristics operating in real-time alongside machine learning")
    
    # Formula Card (Top)
    add_card(s13, Inches(0.8), Inches(1.5), Inches(11.733), Inches(1.4), bg=CARD_BG_ALT, border=BORDER_CYAN, border_width=1.2)
    tb = s13.shapes.add_textbox(Inches(1.05), Inches(1.6), Inches(11.3), Inches(1.2))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "COMPOSITE RISK SCORING FORMULA"
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN
    p = tf.add_paragraph()
    p.text = "R(n) = w_cpu · ΔCPU + w_mem · ΔMem + w_lat · (RTT / RTT_max) + w_bat · (1 - BatteryLevel)"
    p.font.name = "Consolas"; p.font.size = Pt(14); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    p = tf.add_paragraph()
    p.text = "Calibrated Weights: w_cpu = 0.25, w_mem = 0.35, w_lat = 0.25, w_bat = 0.15 | Eviction Threshold: R(n) ≥ 0.70"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.color.rgb = TEXT_LIGHT

    # 3 Evaluated Rules (Bottom 3 Columns)
    rule_w = Inches(3.64)
    rule_h = Inches(4.0)
    rule_top = Inches(3.1)
    
    rules = [
        ("RULE 01 // RAM LEAK", "Progressive Memory Leak Gradient",
         "ΔMem = (Mem_t - Mem_t-k) / k > 5% / min\n\n• Trigger: Evaluated across 3 consecutive 5s heartbeat cycles.\n• Mechanism: Identifies rogue processes steadily eating heap or buffer cache.\n• Action: Node flagged as 'DEGRADED'; prevented from accepting new tasks.", ACCENT_AMBER),
        ("RULE 02 // THERMAL SATURATION", "Thermal Throttling & CPU Freeze",
         "CPU Temp > 85°C OR CPU Utilization > 95% for > 30s\n\n• Trigger: Hardware registers CPU thermal throttling flag.\n• Mechanism: Prevents machine freeze and unexpected thermal shutdown.\n• Action: Lowers scheduling capacity score C(n) to 0; initiates task migration.", ACCENT_RED),
        ("RULE 03 // LATENCY JITTER", "Wi-Fi RTT Latency Escalation",
         "RTT > 300ms OR Missed Heartbeats ≥ 2\n\n• Trigger: Packet delay exceeds network baseline by 400%.\n• Mechanism: Preempts imminent TCP connection drop before socket timeout.\n• Action: Triggers proactive task re-dispatch within ~14 ms.", ACCENT_PURPLE)
    ]
    for i, (rtag, rtitle, rdesc, rcol) in enumerate(rules):
        rx = Inches(0.8) + Inches(i * 4.04)
        add_card(s13, rx, rule_top, rule_w, rule_h, bg=CARD_BG, border=BORDER_MUTED)
        cbar = s13.shapes.add_shape(MSO_SHAPE.RECTANGLE, rx, rule_top, Inches(0.06), rule_h)
        cbar.fill.solid(); cbar.fill.fore_color.rgb = rcol; cbar.line.fill.background()
        
        tb = s13.shapes.add_textbox(rx + Inches(0.18), rule_top + Inches(0.15), Inches(3.3), Inches(3.7))
        tf = tb.text_frame; tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = rtag
        p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = rcol
        p = tf.add_paragraph()
        p.text = rtitle
        p.font.name = "Segoe UI"; p.font.size = Pt(13); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
        p = tf.add_paragraph()
        p.text = f"\n{rdesc}"
        p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 14: CAPACITY-WEIGHTED PLACEMENT & CRYPTOGRAPHIC FENCING
    # =========================================================================
    s14 = create_slide()
    add_header(s14, "13 // SCHEDULING & FENCING", "Capacity-Weighted Partitioning & Monotonic Fencing Tokens",
               "Eliminating stragglers and preventing split-brain race conditions in distributed execution")
    
    # Left Card: Dynamic Capacity Scoring
    add_card(s14, Inches(0.8), Inches(1.5), Inches(5.7), Inches(5.6), bg=CARD_BG, border=BORDER_CYAN, border_width=1.2)
    tb = s14.shapes.add_textbox(Inches(1.05), Inches(1.7), Inches(5.2), Inches(5.2))
    tf = tb.text_frame; tf.word_wrap = True
    
    p = tf.paragraphs[0]
    p.text = "DYNAMIC CAPACITY SCORING FORMULA"
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN
    p = tf.add_paragraph()
    p.text = "C(n) = (α · Cores_n + β · RAM_n) · (1 - Load_n) · (1 - R(n))"
    p.font.name = "Consolas"; p.font.size = Pt(12.5); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    p = tf.add_paragraph()
    p.text = "\n• Proportional Workload Division: A node receives a slice T_i = TotalTasks · [C(n_i) / Σ C(n_j)].\n\n• Heterogeneity Harmonization: Balances hardware disparity (e.g. 4-core laptop vs 16-core desktop).\n\n• Empirical Validation (6:12 Capacity Ratio):"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.color.rgb = TEXT_LIGHT
    
    # Empirical stat box inside card
    p = tf.add_paragraph()
    p.text = "  ▶ Node-1 (4 Cores, 8 GB RAM): Assigned 400 tasks (33.3%)\n  ▶ Node-2 (8 Cores, 16 GB RAM): Assigned 800 tasks (66.7%)\n  ▶ Result: Exact 1:2 workload ratio matching physical compute power."
    p.font.name = "Consolas"; p.font.size = Pt(9.5); p.font.color.rgb = ACCENT_AMBER

    # Right Card: Cryptographic Fencing Invariant
    add_card(s14, Inches(6.833), Inches(1.5), Inches(5.7), Inches(5.6), bg=CARD_BG, border=BORDER_MUTED)
    tb = s14.shapes.add_textbox(Inches(7.083), Inches(1.7), Inches(5.2), Inches(5.2))
    tf = tb.text_frame; tf.word_wrap = True
    
    p = tf.paragraphs[0]
    p.text = "ATOMIC CRYPTOGRAPHIC FENCING TOKENS"
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = ACCENT_PURPLE
    p = tf.add_paragraph()
    p.text = "Invariant: Late results from degraded nodes are atomically rejected"
    p.font.name = "Segoe UI"; p.font.size = Pt(12.5); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "\n• The Zombie Worker Problem: If Node A degrades and its task is reassigned to Node B, Node A might recover and return stale results, corrupting data.\n\n• Monotonic Fencing Token (τ): Every dispatched task is stamped with a strictly increasing 64-bit integer signed by the Raft leader.\n\n• State Machine Guard Invariant:"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.color.rgb = TEXT_LIGHT
    p = tf.add_paragraph()
    p.text = "  When Task T is reassigned: τ_new = τ_old + 1\n  Control Plane check: IF result.token < task.fencing_token THEN REJECT\n  Guarantees: Zero duplicate commits, zero race conditions, zero split-brain."
    p.font.name = "Consolas"; p.font.size = Pt(9.5); p.font.color.rgb = ACCENT_CYAN

    # =========================================================================
    # SLIDE 15: ML PIPELINE: XGBOOST & 14-FEATURE VECTOR
    # =========================================================================
    s15 = create_slide()
    add_header(s15, "14 // MACHINE LEARNING CORE", "Predictive Telemetry Pipeline & 14-Dimensional Feature Vector",
               "Training gradient boosted decision trees on continuous time-series hardware metrics")
    
    # Dataset Banner
    add_card(s15, Inches(0.8), Inches(1.5), Inches(11.733), Inches(1.2), bg=CARD_BG_ALT, border=BORDER_MUTED)
    tb = s15.shapes.add_textbox(Inches(1.05), Inches(1.6), Inches(11.3), Inches(1.0))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "MODEL TRAINING METRICS & DATASET SPECIFICATIONS"
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    p = tf.add_paragraph()
    p.text = "• Dataset: 300,000 empirical and simulated edge telemetry vectors (80/20 train/test split, random seed 42)\n• Model Type: XGBoost Gradient Boosted Classifier (max_depth=6, n_estimators=150, lr=0.08) | Inference Time: ~0.38 ms"
    p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # 14 Features Grid (4 Columns)
    feat_cols = [
        ("CPU & COMPUTE (3)", ["1. cpu_utilization_mean", "2. cpu_utilization_variance", "3. cpu_temp_celsius"], ACCENT_CYAN),
        ("MEMORY DYNAMICS (3)", ["4. ram_used_bytes", "5. ram_leak_gradient_1min", "6. swap_usage_pct"], ACCENT_GREEN),
        ("NETWORK TRANSPORT (3)", ["7. rtt_latency_ms", "8. rtt_jitter_ms", "9. heartbeat_miss_count"], ACCENT_AMBER),
        ("POWER & DOCKER (5)", ["10. disk_io_wait_pct", "11. battery_discharge_rate", "12. battery_remaining_pct", "13. docker_containers_active", "14. thread_count_delta"], ACCENT_PURPLE)
    ]
    col_w = Inches(2.78)
    col_top = Inches(2.9)
    col_h = Inches(4.2)
    
    for i, (gtitle, flist, gcol) in enumerate(feat_cols):
        gx = Inches(0.8) + Inches(i * 2.98)
        add_card(s15, gx, col_top, col_w, col_h, bg=CARD_BG, border=BORDER_MUTED)
        cbar = s15.shapes.add_shape(MSO_SHAPE.RECTANGLE, gx, col_top, Inches(0.06), col_h)
        cbar.fill.solid(); cbar.fill.fore_color.rgb = gcol; cbar.line.fill.background()
        
        tb = s15.shapes.add_textbox(gx + Inches(0.15), col_top + Inches(0.15), Inches(2.55), Inches(3.9))
        tf = tb.text_frame; tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = gtitle
        p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True; p.font.color.rgb = gcol
        for f in flist:
            p = tf.add_paragraph()
            p.text = f"\n{f}"
            p.font.name = "Consolas"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 16: ML MODEL PROGRESSION & FEATURE VALIDATION
    # =========================================================================
    s16 = create_slide()
    add_header(s16, "15 // MODEL BENCHMARKING", "Machine Learning Model Progression & Empirical Validation",
               "Benchmarking candidate algorithms: Logistic Regression, Random Forest, and Tuned XGBoost")
    
    # Table of Model Progression
    table_shape = s16.shapes.add_table(5, 6, Inches(0.8), Inches(1.5), Inches(11.733), Inches(2.8))
    table = table_shape.table
    table.columns[0].width = Inches(2.5)
    table.columns[1].width = Inches(1.8)
    table.columns[2].width = Inches(1.8)
    table.columns[3].width = Inches(1.8)
    table.columns[4].width = Inches(1.8)
    table.columns[5].width = Inches(2.033)
    
    m_headers = ["Candidate Architecture", "Val Accuracy", "ROC-AUC", "Precision", "Recall", "Inference Latency"]
    for j, h in enumerate(m_headers):
        cell = table.cell(0, j)
        cell.fill.solid(); cell.fill.fore_color.rgb = CARD_BG_ALT
        p = cell.text_frame.paragraphs[0]
        p.text = h
        p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True
        p.font.color.rgb = ACCENT_CYAN if j == 1 else TEXT_WHITE

    m_rows = [
        ("Logistic Regression (Baseline)", "62.14%", "0.68", "58.2%", "61.0%", "~0.08 ms"),
        ("Random Forest (100 Trees)", "73.85%", "0.81", "71.4%", "72.9%", "~2.10 ms"),
        ("Untuned XGBoost", "71.50%", "0.79", "69.1%", "70.2%", "~0.35 ms"),
        ("Tuned XGBoost (Selected)", "76.27% (Val) / 88.9% (Train)", "0.84", "77.8%", "75.1%", "~0.38 ms")
    ]
    for i, row in enumerate(m_rows):
        for j, val in enumerate(row):
            cell = table.cell(i + 1, j)
            cell.fill.solid()
            cell.fill.fore_color.rgb = RGBColor(14, 22, 38) if i != 3 else CARD_BG_ALT
            p = cell.text_frame.paragraphs[0]
            p.text = val
            p.font.name = "Segoe UI"; p.font.size = Pt(9.5)
            if i == 3:
                p.font.bold = True
                p.font.color.rgb = ACCENT_GREEN
            else:
                p.font.color.rgb = TEXT_LIGHT

    # Bottom Half: SHAP Feature Importance Rankings
    add_card(s16, Inches(0.8), Inches(4.5), Inches(11.733), Inches(2.6), bg=CARD_BG, border=BORDER_MUTED)
    tb = s16.shapes.add_textbox(Inches(1.05), Inches(4.65), Inches(11.3), Inches(2.3))
    tf = tb.text_frame; tf.word_wrap = True
    
    p = tf.paragraphs[0]
    p.text = "EMPIRICAL FEATURE IMPORTANCE RANKING (SHAP VALUES)"
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    
    shap_items = [
        ("1. ram_leak_gradient_1min (31.4% Importance)", "Strongest early indicator of impending crash; monotonic memory creep precedes heap exhaustion."),
        ("2. rtt_jitter_ms (24.2% Importance)", "Elevated latency variance indicates Wi-Fi packet contention before total TCP socket drop."),
        ("3. cpu_temp_celsius (18.6% Importance)", "Thermal junction saturation triggers OS throttling, creating severe thread starvation."),
        ("4. battery_discharge_rate (14.1% Importance)", "Rapid drain indicates AC disconnection; signals node will suspend within minutes.")
    ]
    for st, sd in shap_items:
        p = tf.add_paragraph()
        p.text = f"•  {st} ➔ {sd}"
        p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 17: DISTRIBUTED CONSENSUS: CUSTOM JAVA RAFT STATE MACHINE
    # =========================================================================
    s17 = create_slide()
    add_header(s17, "16 // CONSENSUS & RAFT", "High-Availability Raft Consensus & Write-Ahead Log (WAL)",
               "Zero external dependencies: 3-replica cluster state replication implemented entirely in Java 21")
    
    raft_cards = [
        ("01 / LEADER ELECTION", "Dynamic Heartbeat & Elections",
         "• Randomized Election Timeout: 150ms – 300ms window avoids split votes during candidate contention.\n\n• Heartbeat Frequency: Leader sends AppendEntriesRPC every 50ms to maintain term authority.\n\n• Instant Failover: If leader terminates, follower transitions to Candidate and elects new leader in < 200 ms.", ACCENT_CYAN),
        ("02 / DISK DURABILITY", "Write-Ahead Log (WAL) Engine",
         "• Synchronous Persistence: Every state mutation (node register, task slice, fenced reroute) is flushed to disk before commit.\n\n• Log Entry Format:\n  [Term: Long, Index: Long, CommandType: Enum, Payload: JSON, Signature]\n\n• Cold Boot Recovery: State machine replayed deterministically from log upon restart.", ACCENT_GREEN),
        ("03 / SPLIT-BRAIN IMMUNITY", "Strict Quorum & Majority Rules",
         "• Quorum Calculation: Q = ⌊N/2⌋ + 1 (2 out of 3 replicas required for all commits).\n\n• Network Partition Tolerance: Minority partition cannot commit entries or advance state machine.\n\n• Zero Dual Leaders: Term monotonic counters guarantee stale leaders cannot overwrite cluster state.", ACCENT_PURPLE)
    ]
    for i, (rtag, rtitle, rdesc, rcol) in enumerate(raft_cards):
        rx = Inches(0.8) + Inches(i * 4.04)
        add_card(s17, rx, Inches(1.5), Inches(3.64), Inches(5.6), bg=CARD_BG, border=BORDER_MUTED)
        cbar = s17.shapes.add_shape(MSO_SHAPE.RECTANGLE, rx, Inches(1.5), Inches(0.06), Inches(5.6))
        cbar.fill.solid(); cbar.fill.fore_color.rgb = rcol; cbar.line.fill.background()
        
        tb = s17.shapes.add_textbox(rx + Inches(0.18), Inches(1.7), Inches(3.3), Inches(5.2))
        tf = tb.text_frame; tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = rtag
        p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = rcol
        p = tf.add_paragraph()
        p.text = rtitle
        p.font.name = "Segoe UI"; p.font.size = Pt(15); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
        p = tf.add_paragraph()
        p.text = f"\n{rdesc}"
        p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 18: IMPLEMENTATION SCALE & SOFTWARE ARCHITECTURE
    # =========================================================================
    s18 = create_slide()
    add_header(s18, "17 // IMPLEMENTATION AUDIT", "Codebase Scale, Technology Stack & Software Architecture",
               "59,420 Lines of Code across 75 Git commits forming a production-grade distributed control plane")
    
    # 4 Stat Metric Cards (Top)
    stat_metrics = [
        ("59,420", "TOTAL LINES OF CODE", "Across 4 core software modules", ACCENT_CYAN),
        ("75", "GIT COMMITS", "Full revision history & CI checks", ACCENT_GREEN),
        ("905", "AUTOMATED TESTS", "100% test pass rate across suite", ACCENT_AMBER),
        ("3", "CLIENT INTERFACES", "Web 3D, Desktop GUI, 'nx' CLI", ACCENT_PURPLE)
    ]
    stat_w = Inches(2.78)
    for i, (val, lbl, sub, scol) in enumerate(stat_metrics):
        sx = Inches(0.8) + Inches(i * 2.98)
        add_card(s18, sx, Inches(1.5), stat_w, Inches(1.4), bg=CARD_BG_ALT, border=BORDER_MUTED)
        tb = s18.shapes.add_textbox(sx + Inches(0.1), Inches(1.58), stat_w - Inches(0.2), Inches(1.2))
        tf = tb.text_frame; tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = val
        p.font.name = "Segoe UI"; p.font.size = Pt(24); p.font.bold = True; p.font.color.rgb = scol
        p = tf.add_paragraph()
        p.text = lbl
        p.font.name = "Segoe UI"; p.font.size = Pt(9); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
        p = tf.add_paragraph()
        p.text = sub
        p.font.name = "Segoe UI"; p.font.size = Pt(8.5); p.font.color.rgb = TEXT_MUTED

    # Bottom Left: Codebase Breakdown
    add_card(s18, Inches(0.8), Inches(3.1), Inches(5.7), Inches(4.0), bg=CARD_BG, border=BORDER_MUTED)
    tb = s18.shapes.add_textbox(Inches(1.05), Inches(3.25), Inches(5.2), Inches(3.7))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "LANGUAGE BREAKDOWN & METRICS"
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN
    code_breakdown = [
        ("Java 21 (Backend Core & Desktop)", "39,284 LOC", "66.1%"),
        ("Python 3.11 (XGBoost ML Pipeline)", "7,568 LOC", "12.7%"),
        ("Frontend (Three.js 3D Web UI & CSS)", "11,553 LOC", "19.4%"),
        ("Protobuf & gRPC Definitions", "1,015 LOC", "1.7%")
    ]
    for lang, loc, pct in code_breakdown:
        p = tf.add_paragraph()
        p.text = f"•  {lang.ljust(35)} {loc.rjust(10)} ({pct})"
        p.font.name = "Consolas"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # Bottom Right: Primary Core Classes
    add_card(s18, Inches(6.833), Inches(3.1), Inches(5.7), Inches(4.0), bg=CARD_BG, border=BORDER_MUTED)
    tb = s18.shapes.add_textbox(Inches(7.083), Inches(3.25), Inches(5.2), Inches(3.7))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "PRIMARY SOFTWARE CLASSES & ROLES"
    p.font.name = "Segoe UI"; p.font.size = Pt(11); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    core_classes = [
        ("ControlPlaneServer.java", "gRPC ingress server & lifecycle coordinator"),
        ("RaftNode.java / RaftLog.java", "3-replica leader election & WAL disk manager"),
        ("PredictiveScheduler.java", "Dual-layer heuristic/ML risk scoring & C(n)"),
        ("NodeAgent.java", "Outbound gRPC client, telemetry scraper & runner"),
        ("TopologyVisualizer.js", "Three.js 3D WebGL orbital cluster dashboard")
    ]
    for cname, crole in core_classes:
        p = tf.add_paragraph()
        p.text = f"▶ {cname}: {crole}"
        p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 19: TESTING, ZERO-MOCK VALIDATION & LIVE BENCHMARKS
    # =========================================================================
    s19 = create_slide()
    add_header(s19, "18 // EMPIRICAL VALIDATION", "Comprehensive Testing Strategy & Empirical Benchmarks",
               "905 automated tests verifying end-to-end resilience and sub-second proactive mitigation")
    
    test_panels = [
        ("01 / TEST SUITE ARCHITECTURE", "905 Tests, 100% Pass Rate",
         "• 827 Java Tests: 93 test classes exercising Raft consensus, mTLS authentication, capacity math, and cryptographic fencing.\n\n• 78 Python Tests: Pytest suite verifying XGBoost feature extraction, pipeline serialization, and telemetry parsing.\n\n• Zero-Mock Verification: Tests run actual gRPC transport sockets on loopback interfaces.", ACCENT_CYAN),
        ("02 / PROACTIVE MITIGATION SPEED", "~14 ms vs 340s Reaction Time",
         "• Next-Gen Control Plane: ~14 ms pre-failure detection-to-reassignment.\n\n• Kubernetes Default: ~340s (40s node-monitor-period + 300s pod-eviction-timeout).\n\n• BOINC Volunteer Grid: ~10 days deadline-driven reallocation.\n\n• Empirical Gain: 99.99% reduction in failure recovery latency without data loss.", ACCENT_GREEN),
        ("03 / CAPACITY ALLOCATION PROOF", "Empirical 6:12 Task Split",
         "• Verification Scenario: 1,200 Prime Range tasks dispatched across 2 heterogeneous nodes (Node 1: 4 cores, Node 2: 8 cores).\n\n• Measured Distribution:\n  - Node 1: Processed 400 tasks (33.3%)\n  - Node 2: Processed 800 tasks (66.7%)\n\n• Validates exact adherence to mathematical capacity scoring C(n).", ACCENT_AMBER)
    ]
    for i, (rtag, rtitle, rdesc, rcol) in enumerate(test_panels):
        rx = Inches(0.8) + Inches(i * 4.04)
        add_card(s19, rx, Inches(1.5), Inches(3.64), Inches(5.6), bg=CARD_BG, border=BORDER_MUTED)
        cbar = s19.shapes.add_shape(MSO_SHAPE.RECTANGLE, rx, Inches(1.5), Inches(0.06), Inches(5.6))
        cbar.fill.solid(); cbar.fill.fore_color.rgb = rcol; cbar.line.fill.background()
        
        tb = s19.shapes.add_textbox(rx + Inches(0.18), Inches(1.7), Inches(3.3), Inches(5.2))
        tf = tb.text_frame; tf.word_wrap = True
        p = tf.paragraphs[0]
        p.text = rtag
        p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = rcol
        p = tf.add_paragraph()
        p.text = rtitle
        p.font.name = "Segoe UI"; p.font.size = Pt(14); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
        p = tf.add_paragraph()
        p.text = f"\n{rdesc}"
        p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.color.rgb = TEXT_LIGHT

    # =========================================================================
    # SLIDE 20: STRATEGIC VALUE, LIMITATIONS, ROADMAP & REFERENCES
    # =========================================================================
    s20 = create_slide()
    add_header(s20, "19 // CONCLUSION & REFERENCES", "Strategic Value, Operational Boundaries, Roadmap & References",
               "Academic conclusions, commercial impact, future research roadmap, and key citations")
    
    quad_w = Inches(5.7)
    quad_h = Inches(2.65)
    
    # Q1: Strategic Value
    add_card(s20, Inches(0.8), Inches(1.5), quad_w, quad_h)
    tb = s20.shapes.add_textbox(Inches(1.05), Inches(1.65), Inches(5.2), Inches(2.3))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "STRATEGIC & ENTERPRISE VALUE"
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN
    p = tf.add_paragraph()
    p.text = "Zero-Cost Edge Infrastructure"
    p.font.name = "Segoe UI"; p.font.size = Pt(13); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "• Unlocks latent computing power across universities, labs, and enterprise offices without cloud bills.\n• Proactive fault tolerance eliminates task loss and reduces SLA violation penalties by 99%."
    p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # Q2: Limitations
    add_card(s20, Inches(6.833), Inches(1.5), quad_w, quad_h)
    tb = s20.shapes.add_textbox(Inches(7.083), Inches(1.65), Inches(5.2), Inches(2.3))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "MEASURED OPERATIONAL BOUNDARIES"
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True; p.font.color.rgb = ACCENT_AMBER
    p = tf.add_paragraph()
    p.text = "Current Architectural Constraints"
    p.font.name = "Segoe UI"; p.font.size = Pt(13); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "• Optimized for embarrassingly parallel batch jobs & stateless containers; stateful live RAM migration is omitted.\n• Control Plane requires a reachable IP (or port relay) accessible to edge nodes."
    p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # Q3: Future Roadmap
    add_card(s20, Inches(0.8), Inches(4.35), quad_w, quad_h)
    tb = s20.shapes.add_textbox(Inches(1.05), Inches(4.5), Inches(5.2), Inches(2.3))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "FUTURE RESEARCH ROADMAP"
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True; p.font.color.rgb = ACCENT_GREEN
    p = tf.add_paragraph()
    p.text = "Next-Generation Enhancements"
    p.font.name = "Segoe UI"; p.font.size = Pt(13); p.font.bold = True; p.font.color.rgb = TEXT_WHITE
    p = tf.add_paragraph()
    p.text = "• Reinforcement Learning (PPO) for autonomous scheduling weight tuning.\n• WebAssembly (Wasm) worker sandboxing for zero-footprint browser nodes.\n• GPU/NPU-accelerated edge model inference distribution."
    p.font.name = "Segoe UI"; p.font.size = Pt(9.5); p.font.color.rgb = TEXT_LIGHT

    # Q4: Key References & Thank You
    add_card(s20, Inches(6.833), Inches(4.35), quad_w, quad_h)
    tb = s20.shapes.add_textbox(Inches(7.083), Inches(4.5), Inches(5.2), Inches(2.3))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = "KEY ACADEMIC REFERENCES"
    p.font.name = "Segoe UI"; p.font.size = Pt(10.5); p.font.bold = True; p.font.color.rgb = ACCENT_PURPLE
    p = tf.add_paragraph()
    p.text = "[1] Ongaro & Ousterhout. In Search of an Understandable Consensus (Raft). USENIX ATC '14.\n[2] Verma et al. Large-scale cluster management at Google with Borg. EuroSys '15.\n[3] Chen & Guestrin. XGBoost: Scalable Tree Boosting System. ACM KDD '16.\n[4] Anderson, D. P. BOINC: Public-Resource Computing. IEEE GRID '04."
    p.font.name = "Segoe UI"; p.font.size = Pt(9); p.font.color.rgb = TEXT_LIGHT
    p = tf.add_paragraph()
    p.text = "AMC Engineering College | Dept. of CSE | Thank You — Q&A"
    p.font.name = "Segoe UI"; p.font.size = Pt(10); p.font.bold = True; p.font.color.rgb = ACCENT_CYAN

    # Save Presentation to Both Locations
    out_path1 = r"C:\Users\LALITH\Documents\next-gen-control-plane\Control_Plane_Final_Presentation_20Slides.pptx"
    out_path2 = r"C:\Users\LALITH\Documents\next-gen-control-plane\extracted_presentation\Control_Plane_Presentation_20Slides.pptx"
    prs.save(out_path1)
    prs.save(out_path2)
    print(f"SUCCESS: Generated 20 slides in {out_path1} and {out_path2}")
    print(f"Total slides count: {len(prs.slides)}")

if __name__ == "__main__":
    build_presentation()
